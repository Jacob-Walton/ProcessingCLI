package org.konpeki;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Java expressions on a paused frame, for watches, hovers, the debug console and breakpoint conditions
 */
final class Eval {
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    // A type name, like Math in Math.abs()
    private record TypeName(ReferenceType type) {}

    private static final Map<String, ExpressionTree> parsed = new ConcurrentHashMap<>();

    private final VirtualMachine vm;
    private final ThreadReference thread;
    private final int depth;
    private final String sketchClass;

    Eval(ThreadReference thread, int depth, String sketchClass) {
        this.vm = thread.virtualMachine();
        this.thread = thread;
        this.depth = depth;
        this.sketchClass = sketchClass;
    }

    // Each call can run code in the sketch
    static final Set<ThreadReference> evaluating = ConcurrentHashMap.newKeySet();

    Value eval(String expression) throws Failure {
        evaluating.add(thread);
        try {
            return evalInner(expression);
        } finally {
            evaluating.remove(thread);
        }
    }

    private Value evalInner(String expression) throws Failure {
        Object result = eval(parse(expression));
        if (result instanceof TypeName) throw new Failure(expression + " is a type");
        return (Value) result;
    }

    // Wrapped in a field so javac parses it as an expression
    private static ExpressionTree parse(String expression) throws Failure {
        ExpressionTree cached = parsed.get(expression);
        if (cached != null) return cached;
        String source = "class E { Object e = (" + expression + "\n); }";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///E.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        List<String> errors = new ArrayList<>();
        JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(
                        null,
                        null,
                        d -> {
                            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d.getMessage(null));
                        },
                        List.of("-proc:none"),
                        null,
                        List.of(file));
        try {
            ClassTree type =
                    (ClassTree) task.parse().iterator().next().getTypeDecls().getFirst();
            if (!errors.isEmpty()) throw new Failure(errors.getFirst());
            ExpressionTree tree = ((ParenthesizedTree)
                            ((VariableTree) type.getMembers().getFirst()).getInitializer())
                    .getExpression();
            parsed.put(expression, tree);
            return tree;
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            throw new Failure("Can't read that as an expression");
        }
    }

    // Again for each use, method calls invalidate it
    private StackFrame frame() throws Failure {
        try {
            return thread.frame(depth);
        } catch (IncompatibleThreadStateException | IndexOutOfBoundsException e) {
            throw new Failure("The sketch is running");
        }
    }

    private Object eval(Tree tree) throws Failure {
        return switch (tree) {
            case ParenthesizedTree p -> eval(p.getExpression());
            case LiteralTree l -> literal(l.getValue());
            case IdentifierTree id -> name(id.getName().toString());
            case MemberSelectTree select -> member(select);
            case ArrayAccessTree access -> element(access);
            case MethodInvocationTree call -> call(call);
            case UnaryTree unary -> unary(unary);
            case BinaryTree binary -> binary(binary);
            case ConditionalExpressionTree c ->
                truth(value(c.getCondition())) ? eval(c.getTrueExpression()) : eval(c.getFalseExpression());
            case TypeCastTree cast ->
                cast(value(cast.getExpression()), cast.getType().toString());
            case InstanceOfTree test -> instance(test);
            case AssignmentTree assign -> assign(assign.getVariable(), value(assign.getExpression()));
            case CompoundAssignmentTree assign ->
                assign(
                        assign.getVariable(),
                        arithmetic(
                                binaryOperator(assign.getKind()),
                                value(assign.getVariable()),
                                value(assign.getExpression())));
            default ->
                throw new Failure("Can't evaluate "
                        + tree.getKind().toString().toLowerCase().replace('_', ' '));
        };
    }

    private Value value(Tree tree) throws Failure {
        Object result = eval(tree);
        if (result instanceof TypeName type) throw new Failure(type.type().name() + " is a type");
        return (Value) result;
    }

    private Value literal(Object value) {
        return switch (value) {
            case null -> null;
            case Integer i -> vm.mirrorOf(i);
            case Long l -> vm.mirrorOf(l);
            case Float f -> vm.mirrorOf(f);
            case Double d -> vm.mirrorOf(d);
            case Boolean b -> vm.mirrorOf(b);
            case Character c -> vm.mirrorOf(c);
            default -> vm.mirrorOf(value.toString());
        };
    }

    // Locals, then fields of this and the classes around it, then types
    private Object name(String name) throws Failure {
        StackFrame frame = frame();
        if (name.equals("this")) return frame.thisObject();
        try {
            LocalVariable local = frame.visibleVariableByName(name);
            if (local != null) return frame.getValue(local);
        } catch (AbsentInformationException ignored) {
            // No locals
        }
        for (ObjectReference self = frame.thisObject(); self != null; self = outer(self)) {
            Field field = self.referenceType().fieldByName(name);
            if (field != null) return field.isStatic() ? self.referenceType().getValue(field) : self.getValue(field);
        }
        ReferenceType declaring = frame.location().declaringType();
        Field field = declaring.fieldByName(name);
        if (field != null && field.isStatic()) return declaring.getValue(field);
        ReferenceType type = type(name);
        if (type != null) return new TypeName(type);
        throw new Failure(name + " isn't defined here");
    }

    private static ObjectReference outer(ObjectReference object) {
        Field outer = object.referenceType().fieldByName("this$0");
        return outer != null && object.getValue(outer) instanceof ObjectReference o ? o : null;
    }

    // Only classes already loaded, which the sketch's own are
    private ReferenceType type(String name) {
        for (String candidate : List.of(
                sketchClass + "$" + name,
                name,
                "java.lang." + name,
                "processing.core." + name,
                "processing.data." + name,
                "java.util." + name)) {
            List<ReferenceType> found = vm.classesByName(candidate);
            if (!found.isEmpty()) return found.getFirst();
        }
        return null;
    }

    private Object member(MemberSelectTree select) throws Failure {
        String name = select.getIdentifier().toString();
        Object receiver;
        try {
            receiver = eval(select.getExpression());
        } catch (Failure f) {
            // java.util.Arrays and the like
            ReferenceType type = type(select.toString());
            if (type != null) return new TypeName(type);
            throw f;
        }
        switch (receiver) {
            case ArrayReference array
            when name.equals("length") -> {
                return vm.mirrorOf(array.length());
            }
            case TypeName type -> {
                Field field = type.type().fieldByName(name);
                if (field != null && field.isStatic()) return type.type().getValue(field);
                ReferenceType nested = type(type.type().name() + "$" + name);
                if (nested != null) return new TypeName(nested);
                throw new Failure(type.type().name() + " has no static " + name);
            }
            case ObjectReference object -> {
                Field field = object.referenceType().fieldByName(name);
                if (field != null)
                    return field.isStatic() ? object.referenceType().getValue(field) : object.getValue(field);
                throw new Failure(shortName(object.referenceType().name()) + " has no field " + name);
            }
            case null -> throw new Failure("null has no field " + name);
            default -> throw new Failure(receiver + " has no field " + name);
        }
    }

    private Value element(ArrayAccessTree access) throws Failure {
        Value array = value(access.getExpression());
        long index = number(value(access.getIndex())).longValue();
        if (!(array instanceof ArrayReference a)) throw new Failure("Not an array");
        if (index < 0 || index >= a.length())
            throw new Failure("Index " + index + " is outside 0 to " + (a.length() - 1));
        return a.getValue((int) index);
    }

    private Value call(MethodInvocationTree call) throws Failure {
        List<Value> args = new ArrayList<>();
        for (ExpressionTree arg : call.getArguments()) args.add(value(arg));
        Tree select = call.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            Object receiver = eval(member.getExpression());
            String name = member.getIdentifier().toString();
            if (receiver instanceof TypeName type) return invokeStatic(type.type(), name, args);
            if (receiver instanceof ObjectReference object) return invoke(object, name, args);
            throw new Failure(
                    receiver == null ? "Calling " + name + "() on null" : "Can't call methods on " + receiver);
        }
        // dist(), or a method of the class around this
        String name = select.toString();
        StackFrame frame = frame();
        for (ObjectReference self = frame.thisObject(); self != null; self = outer(self)) {
            if (!methods(self.referenceType(), name, args.size()).isEmpty()) return invoke(self, name, args);
        }
        return invokeStatic(frame.location().declaringType(), name, args);
    }

    private static List<Method> methods(ReferenceType type, String name, int count) {
        return type.methodsByName(name).stream()
                .filter(m -> m.argumentTypeNames().size() == count || m.isVarArgs())
                .toList();
    }

    // The first overload whose parameters take the arguments, exact matches first
    private Method pick(ReferenceType type, String name, List<Value> args) throws Failure {
        List<Method> candidates = methods(type, name, args.size());
        if (candidates.isEmpty())
            throw new Failure(shortName(type.name()) + " has no " + name + "() for " + args.size() + " arguments");
        for (boolean exact : List.of(true, false)) {
            for (Method method : candidates) {
                if (method.isVarArgs()) continue;
                if (fits(method.argumentTypeNames(), args, exact)) return method;
            }
        }
        throw new Failure("None of " + shortName(type.name()) + "." + name + "() takes those arguments");
    }

    private boolean fits(List<String> types, List<Value> args, boolean exact) {
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            Value arg = args.get(i);
            String actual = arg == null ? null : arg.type().name();
            if (arg == null) {
                if (PRIMITIVES.contains(type)) return false;
            } else if (PRIMITIVES.contains(type)) {
                if (!PRIMITIVES.contains(actual)) return false;
                if (exact
                        ? !type.equals(actual)
                        : WIDER.indexOf(type) < WIDER.indexOf(actual)
                                || type.equals("boolean") != actual.equals("boolean")) return false;
            } else if (exact && !type.equals(actual)) {
                return false;
            } else if (PRIMITIVES.contains(actual) && !type.equals("java.lang.Object")) {
                return false;
            }
        }
        return true;
    }

    private static final List<String> PRIMITIVES =
            List.of("byte", "short", "char", "int", "long", "float", "double", "boolean");
    private static final List<String> WIDER = List.of("byte", "short", "char", "int", "long", "float", "double");

    private Value invoke(ObjectReference object, String name, List<Value> args) throws Failure {
        Method method = pick(object.referenceType(), name, args);
        try {
            return object.invokeMethod(thread, method, convert(method, args), ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException e) {
            throw new Failure(
                    name + "() threw " + shortName(e.exception().referenceType().name()));
        } catch (Exception e) {
            throw new Failure("Couldn't call " + name + "(): " + e.getMessage());
        }
    }

    private Value invokeStatic(ReferenceType type, String name, List<Value> args) throws Failure {
        Method method = pick(type, name, args);
        try {
            List<Value> converted = convert(method, args);
            return switch (type) {
                case ClassType c -> c.invokeMethod(thread, method, converted, ObjectReference.INVOKE_SINGLE_THREADED);
                case InterfaceType i ->
                    i.invokeMethod(thread, method, converted, ObjectReference.INVOKE_SINGLE_THREADED);
                default -> throw new Failure("Can't call " + name + "() here");
            };
        } catch (InvocationException e) {
            throw new Failure(
                    name + "() threw " + shortName(e.exception().referenceType().name()));
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            throw new Failure("Couldn't call " + name + "(): " + e.getMessage());
        }
    }

    // Widened to what the method takes, int to float for dist(1, 2, 3, 4)
    private List<Value> convert(Method method, List<Value> args) throws Failure {
        List<Value> converted = new ArrayList<>();
        List<String> types = method.argumentTypeNames();
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            converted.add(
                    PRIMITIVES.contains(types.get(i)) && arg instanceof PrimitiveValue ? cast(arg, types.get(i)) : arg);
        }
        return converted;
    }

    private Value unary(UnaryTree unary) throws Failure {
        Tree.Kind kind = unary.getKind();
        if (kind == Tree.Kind.PREFIX_INCREMENT
                || kind == Tree.Kind.PREFIX_DECREMENT
                || kind == Tree.Kind.POSTFIX_INCREMENT
                || kind == Tree.Kind.POSTFIX_DECREMENT) {
            Value before = value(unary.getExpression());
            boolean up = kind == Tree.Kind.PREFIX_INCREMENT || kind == Tree.Kind.POSTFIX_INCREMENT;
            Value after = arithmetic(up ? "+" : "-", before, vm.mirrorOf(1));
            assign(unary.getExpression(), after);
            return kind == Tree.Kind.PREFIX_INCREMENT || kind == Tree.Kind.PREFIX_DECREMENT ? after : before;
        }
        Value operand = value(unary.getExpression());
        return switch (kind) {
            case LOGICAL_COMPLEMENT -> vm.mirrorOf(!truth(operand));
            case UNARY_MINUS -> arithmetic("-", vm.mirrorOf(0), operand);
            case UNARY_PLUS -> operand;
            case BITWISE_COMPLEMENT -> arithmetic("^", operand, vm.mirrorOf(-1));
            default -> throw new Failure("Can't evaluate " + kind);
        };
    }

    private Value binary(BinaryTree binary) throws Failure {
        Tree.Kind kind = binary.getKind();
        // Short circuit like Java
        if (kind == Tree.Kind.CONDITIONAL_AND) {
            return vm.mirrorOf(truth(value(binary.getLeftOperand())) && truth(value(binary.getRightOperand())));
        }
        if (kind == Tree.Kind.CONDITIONAL_OR) {
            return vm.mirrorOf(truth(value(binary.getLeftOperand())) || truth(value(binary.getRightOperand())));
        }
        Value left = value(binary.getLeftOperand()), right = value(binary.getRightOperand());
        return switch (kind) {
            case EQUAL_TO -> vm.mirrorOf(same(left, right));
            case NOT_EQUAL_TO -> vm.mirrorOf(!same(left, right));
            case LESS_THAN -> vm.mirrorOf(compare(left, right) < 0);
            case GREATER_THAN -> vm.mirrorOf(compare(left, right) > 0);
            case LESS_THAN_EQUAL -> vm.mirrorOf(compare(left, right) <= 0);
            case GREATER_THAN_EQUAL -> vm.mirrorOf(compare(left, right) >= 0);
            default -> arithmetic(binaryOperator(kind), left, right);
        };
    }

    private static String binaryOperator(Tree.Kind kind) throws Failure {
        return switch (kind) {
            case PLUS, PLUS_ASSIGNMENT -> "+";
            case MINUS, MINUS_ASSIGNMENT -> "-";
            case MULTIPLY, MULTIPLY_ASSIGNMENT -> "*";
            case DIVIDE, DIVIDE_ASSIGNMENT -> "/";
            case REMAINDER, REMAINDER_ASSIGNMENT -> "%";
            case AND, AND_ASSIGNMENT -> "&";
            case OR, OR_ASSIGNMENT -> "|";
            case XOR, XOR_ASSIGNMENT -> "^";
            case LEFT_SHIFT, LEFT_SHIFT_ASSIGNMENT -> "<<";
            case RIGHT_SHIFT, RIGHT_SHIFT_ASSIGNMENT -> ">>";
            case UNSIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> ">>>";
            default -> throw new Failure("Can't evaluate " + kind);
        };
    }

    // Java's numeric promotion: double, then float, then long, then int
    private Value arithmetic(String op, Value left, Value right) throws Failure {
        if (op.equals("+") && (left instanceof StringReference || right instanceof StringReference)) {
            return vm.mirrorOf(text(left) + text(right));
        }
        if (left instanceof BooleanValue a && right instanceof BooleanValue b) {
            return switch (op) {
                case "&" -> vm.mirrorOf(a.value() & b.value());
                case "|" -> vm.mirrorOf(a.value() | b.value());
                case "^" -> vm.mirrorOf(a.value() ^ b.value());
                default -> throw new Failure("Can't use " + op + " on booleans");
            };
        }
        Number a = number(left), b = number(right);
        String type = op.startsWith("<<") || op.startsWith(">>") ? promoted(left, left) : promoted(left, right);
        try {
            return switch (type) {
                case "double" ->
                    vm.mirrorOf(
                            switch (op) {
                                case "+" -> a.doubleValue() + b.doubleValue();
                                case "-" -> a.doubleValue() - b.doubleValue();
                                case "*" -> a.doubleValue() * b.doubleValue();
                                case "/" -> a.doubleValue() / b.doubleValue();
                                case "%" -> a.doubleValue() % b.doubleValue();
                                default -> throw new Failure("Can't use " + op + " on decimals");
                            });
                case "float" ->
                    vm.mirrorOf(
                            switch (op) {
                                case "+" -> a.floatValue() + b.floatValue();
                                case "-" -> a.floatValue() - b.floatValue();
                                case "*" -> a.floatValue() * b.floatValue();
                                case "/" -> a.floatValue() / b.floatValue();
                                case "%" -> a.floatValue() % b.floatValue();
                                default -> throw new Failure("Can't use " + op + " on decimals");
                            });
                case "long" -> vm.mirrorOf(integral(op, a.longValue(), b.longValue()));
                default -> vm.mirrorOf((int) integral(op, a.intValue(), b.intValue()));
            };
        } catch (ArithmeticException e) {
            throw new Failure(e.getMessage());
        }
    }

    private static long integral(String op, long a, long b) throws Failure {
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            case "%" -> a % b;
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            case "<<" -> a << b;
            case ">>" -> a >> b;
            case ">>>" -> a >>> b;
            default -> throw new Failure("Can't use " + op);
        };
    }

    private static String promoted(Value left, Value right) {
        String a = left.type().name(), b = right.type().name();
        for (String type : List.of("double", "float", "long")) if (a.equals(type) || b.equals(type)) return type;
        return "int";
    }

    private Number number(Value value) throws Failure {
        return switch (value) {
            case CharValue c -> (int) c.value();
            case DoubleValue d -> d.value();
            case FloatValue f -> f.value();
            case LongValue l -> l.value();
            case PrimitiveValue p when !(p instanceof BooleanValue) -> p.intValue();
            case ObjectReference o when unboxed(o) instanceof PrimitiveValue p -> number(p);
            case null -> throw new Failure("null isn't a number");
            default -> throw new Failure(describe(value) + " isn't a number");
        };
    }

    // Integer, Float and the like hold their value in a field called value
    private static Value unboxed(ObjectReference object) {
        Field field = object.referenceType().name().startsWith("java.lang.")
                ? object.referenceType().fieldByName("value")
                : null;
        return field == null ? null : object.getValue(field);
    }

    private boolean truth(Value value) throws Failure {
        if (value instanceof BooleanValue b) return b.value();
        if (value instanceof ObjectReference o && unboxed(o) instanceof BooleanValue b) return b.value();
        throw new Failure(describe(value) + " isn't true or false");
    }

    private boolean same(Value left, Value right) throws Failure {
        if (left == null || right == null) return left == right;
        if (left instanceof BooleanValue || right instanceof BooleanValue) return truth(left) == truth(right);
        if (left instanceof PrimitiveValue || right instanceof PrimitiveValue) return compare(left, right) == 0;
        return left.equals(right);
    }

    private int compare(Value left, Value right) throws Failure {
        return Double.compare(number(left).doubleValue(), number(right).doubleValue());
    }

    private Value cast(Value value, String type) throws Failure {
        if (!PRIMITIVES.contains(type) || value instanceof BooleanValue) return value;
        Number n = number(value);
        return switch (type) {
            case "int" -> vm.mirrorOf(n.intValue());
            case "long" -> vm.mirrorOf(n.longValue());
            case "float" -> vm.mirrorOf(n.floatValue());
            case "double" -> vm.mirrorOf(n.doubleValue());
            case "short" -> vm.mirrorOf(n.shortValue());
            case "byte" -> vm.mirrorOf(n.byteValue());
            case "char" -> vm.mirrorOf((char) n.intValue());
            default -> value;
        };
    }

    private Value instance(InstanceOfTree test) throws Failure {
        Value value = value(test.getExpression());
        ReferenceType type = type(test.getType().toString());
        if (type == null) throw new Failure(test.getType() + " isn't loaded");
        if (!(value instanceof ObjectReference object)) return vm.mirrorOf(false);
        for (ReferenceType t = object.referenceType();
                t != null;
                t = t instanceof ClassType c ? c.superclass() : null) {
            if (t.equals(type)) return vm.mirrorOf(true);
            if (t instanceof ClassType c && c.allInterfaces().contains(type)) return vm.mirrorOf(true);
        }
        return vm.mirrorOf(false);
    }

    // x = 5, ball.x = 5 and balls[0] = null, from the debug console
    Value assign(Tree target, Value value) throws Failure {
        try {
            switch (target) {
                case ParenthesizedTree p -> {
                    return assign(p.getExpression(), value);
                }
                case IdentifierTree id -> {
                    String name = id.getName().toString();
                    StackFrame frame = frame();
                    try {
                        LocalVariable local = frame.visibleVariableByName(name);
                        if (local != null) {
                            Value converted = fit(value, local.type());
                            frame.setValue(local, converted);
                            return converted;
                        }
                    } catch (AbsentInformationException ignored) {
                        // No locals
                    }
                    for (ObjectReference self = frame.thisObject(); self != null; self = outer(self)) {
                        Field field = self.referenceType().fieldByName(name);
                        if (field != null) return setField(self, field, value);
                    }
                    throw new Failure(name + " isn't defined here");
                }
                case MemberSelectTree select -> {
                    if (!(value(select.getExpression()) instanceof ObjectReference object))
                        throw new Failure("Can't set that");
                    Field field = object.referenceType()
                            .fieldByName(select.getIdentifier().toString());
                    if (field == null) throw new Failure("No field " + select.getIdentifier());
                    return setField(object, field, value);
                }
                case ArrayAccessTree access -> {
                    if (!(value(access.getExpression()) instanceof ArrayReference array))
                        throw new Failure("Not an array");
                    int index = number(value(access.getIndex())).intValue();
                    Value converted = fit(value, ((ArrayType) array.type()).componentType());
                    array.setValue(index, converted);
                    return converted;
                }
                default -> throw new Failure("Can't set that");
            }
        } catch (Failure f) {
            throw f;
        } catch (ClassNotLoadedException e) {
            throw new Failure(e.className() + " isn't loaded");
        } catch (Exception e) {
            throw new Failure("Can't set that: " + e.getMessage());
        }
    }

    // For setting a variable from the variables view
    Value assign(String target, String expression) throws Failure {
        return assign(parse(target), eval(expression));
    }

    private Value setField(ObjectReference object, Field field, Value value) throws Exception {
        Value converted = fit(value, field.type());
        if (field.isStatic() && object.referenceType() instanceof ClassType c) c.setValue(field, converted);
        else object.setValue(field, converted);
        return converted;
    }

    Value fit(Value value, Type type) throws Failure {
        return value instanceof PrimitiveValue && PRIMITIVES.contains(type.name()) ? cast(value, type.name()) : value;
    }

    // String concatenation, toString() for objects like Java does
    String text(Value value) throws Failure {
        return switch (value) {
            case null -> "null";
            case StringReference s -> s.value();
            case CharValue c -> String.valueOf(c.value());
            case PrimitiveValue p -> p.toString();
            case ObjectReference o -> text(invoke(o, "toString", List.of()));
            default -> value.toString();
        };
    }

    private String describe(Value value) {
        return value == null ? "null" : value instanceof StringReference s ? '"' + s.value() + '"' : value.toString();
    }

    private String shortName(String name) {
        return name.substring(name.lastIndexOf('.') + 1).replace(sketchClass + "$", "");
    }
}
