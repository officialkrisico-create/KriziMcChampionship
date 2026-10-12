package nl.kmc.hitw.wall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of wall types (data, from config) and wall shapes (code that turns that data into
 * rectangles). Adding a variant is either a new config entry reusing an existing shape, or — for
 * something genuinely new — {@link #registerShape(String, WallShapeBuilder)} plus a config entry
 * that names it.
 */
public final class WallTypes {

    /** Narrowest gap ever generated, so event scaling can never make a hole impossible to enter. */
    public static final double MIN_GAP = 1.4;
    /** Minimum solid space kept between two gaps of one wall. */
    private static final double GAP_SEPARATION = 1.5;

    private final Map<String, WallTypeDef> defs = new LinkedHashMap<>();
    private final Map<String, WallShapeBuilder> builders = new LinkedHashMap<>();

    public WallTypes() {
        registerShape("HOLES", WallTypes::buildHoles);
        registerShape("BAR",   WallTypes::buildBar);
    }

    public void registerShape(String shape, WallShapeBuilder builder) { builders.put(shape.toUpperCase(Locale.ROOT), builder); }

    public void register(WallTypeDef def) { defs.put(def.id().toUpperCase(Locale.ROOT), def); }

    public void clear() { defs.clear(); }

    public WallTypeDef get(String id) { return id == null ? null : defs.get(id.toUpperCase(Locale.ROOT)); }

    public Collection<WallTypeDef> all() { return defs.values(); }

    public List<String> ids() { return new ArrayList<>(defs.keySet()); }

    /** Builds a wall of the given type. Throws if the type or its shape is unknown. */
    public WallShape build(String typeId, WallBuildContext ctx) {
        WallTypeDef def = get(typeId);
        if (def == null) throw new IllegalArgumentException("Unknown wall type: " + typeId);
        WallShapeBuilder builder = builders.get(def.shape().toUpperCase(Locale.ROOT));
        if (builder == null) throw new IllegalArgumentException("Wall type " + def.id() + " uses unknown shape " + def.shape());
        return builder.build(def, ctx);
    }

    // ── Built-in shapes ──────────────────────────────────────────────────────

    private static WallShape buildHoles(WallTypeDef def, WallBuildContext ctx) {
        double gap = Math.max(MIN_GAP, def.gapWidth() * ctx.gapScale());
        List<Double> centers = GapPlacer.place(Math.max(1, def.holes()), gap, ctx, GAP_SEPARATION);

        List<Double> sorted = new ArrayList<>(centers);
        sorted.sort(Double::compare);

        List<Rect> solids = new ArrayList<>();
        double cursor = -ctx.halfWidth();
        for (double c : sorted) {
            double start = c - gap / 2, end = c + gap / 2;
            if (start - cursor > 0.01) solids.add(new Rect(cursor, start, 0, def.height()));
            cursor = end;
        }
        if (ctx.halfWidth() - cursor > 0.01) solids.add(new Rect(cursor, ctx.halfWidth(), 0, def.height()));

        return new WallShape(def.id(), def.displayName(), def.material(), List.copyOf(solids),
                ctx.halfWidth(), def.thickness(), def.speedMult(), centers.get(0));
    }

    private static WallShape buildBar(WallTypeDef def, WallBuildContext ctx) {
        List<Rect> solids = List.of(new Rect(-ctx.halfWidth(), ctx.halfWidth(), def.yMin(), def.yMax()));
        return new WallShape(def.id(), def.displayName(), def.material(), solids,
                ctx.halfWidth(), def.thickness(), def.speedMult(), Double.NaN);
    }
}
