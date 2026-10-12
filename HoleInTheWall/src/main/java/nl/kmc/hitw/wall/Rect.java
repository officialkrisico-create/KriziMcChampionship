package nl.kmc.hitw.wall;

/**
 * A solid rectangle of a wall in the wall's own 2-D plane: {@code u} runs sideways across the wall
 * (left → right), {@code y} runs upward from the platform surface. A wall is a list of these;
 * everything not covered by a rectangle is open air a player can pass through.
 */
public record Rect(double u0, double u1, double y0, double y1) {
    public double width()  { return u1 - u0; }
    public double height() { return y1 - y0; }
}
