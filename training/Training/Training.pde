import java.util.HashMap;

ArrayList<Ball> balls = new ArrayList<Ball>();
HashMap<String, Integer> counts = new HashMap<String, Integer>();
final float PADDING = 20;
color bg = #202830;
PFont font;
PImage img;
String[] lines;

void setup() {
  size(400, 300);
  font = createFont("SansSerif", 14);
  textFont(font);

  for (int i = 0; i < 10; i++) {
    balls.add(new Ball(random(width), random(height)));
  }

  String tmp = System.getProperty("java.io.tmpdir");
  PImage made = createImage(32, 32, RGB);
  made.loadPixels();
  for (int i = 0; i < made.pixels.length; i++) {
    made.pixels[i] = lerpColor(#FF0000, #0000FF, i / (float) made.pixels.length);
  }
  made.updatePixels();
  made.save(tmp + "/pcli_training.png");
  img = loadImage(tmp + "/pcli_training.png");

  saveStrings(tmp + "/pcli_training.txt", new String[] { "alpha", "beta", "gamma" });
  lines = loadStrings(tmp + "/pcli_training.txt");
  for (String s : lines) {
    counts.put(s, s.length());
  }
}

void draw() {
  background(bg);
  noStroke();

  balls.removeIf(b -> b.pos.x < -100);
  balls.sort((a, b) -> Float.compare(a.pos.y, b.pos.y));
  for (Ball b : balls) {
    b.update();
    b.show();
  }

  pushMatrix();
  translate(width / 2, height / 2);
  rotate(radians(frameCount));
  star(0, 0, 20, 40, 5);
  popMatrix();

  noFill();
  stroke(255);
  bezier(PADDING, PADDING, 100, 0, 300, height, width - PADDING, height - PADDING);
  float n = noise(frameCount * 0.01) * 50;
  line(PADDING, n, width - PADDING, n);

  image(img, width - 40, 8);

  fill(255);
  text(label(), PADDING, height - PADDING);
}

void mousePressed() {
  balls.add(new Ball(mouseX, mouseY));
}

void keyPressed() {
  switch (key) {
    case 'c': balls.clear(); break;
    default: println("key: " + key);
  }
}