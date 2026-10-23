interface Shape {
  void show();
}

class Ball implements Shape {
  PVector pos, vel;

  Ball(float x, float y) {
    pos = new PVector(x, y);
    vel = PVector.random2D().mult(2.5);
  }

  void update() {
    pos.add(vel);
    if (pos.x < 0 || pos.x > width) vel.x *= -1;
    if (pos.y < 0 || pos.y > height) vel.y *= -1;
    pos.x = constrain(pos.x, 0, width);
  }

  void show() {
    fill(map(pos.x, 0, width, 50, 255), 120, 200);
    ellipse(pos.x, pos.y, 16, 16);
  }
}

void star(float x, float y, float r1, float r2, int points) {
  float angle = TWO_PI / points;
  beginShape();
  for (float a = 0; a < TWO_PI; a += angle) {
    vertex(x + cos(a) * r2, y + sin(a) * r2);
    vertex(x + cos(a + angle / 2) * r1, y + sin(a + angle / 2) * r1);
  }
  endShape(CLOSE);
}

String label() {
  StringBuilder sb = new StringBuilder();
  sb.append("balls ").append(balls.size());
  sb.append(String.format(" | %.1f fps", frameRate));
  sb.append(" | t=").append(nf(millis() / 1000.0, 0, 2));
  int total = 0;
  for (int v : counts.values()) total += v;
  return sb.append(" | ").append(total).toString();
}