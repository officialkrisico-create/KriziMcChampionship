package nl.kmc.hitw.wall;

/** Turns a {@link WallTypeDef} into a concrete {@link WallShape} for one wall. Register new ones via {@link WallTypes#registerShape}. */
@FunctionalInterface
public interface WallShapeBuilder {
    WallShape build(WallTypeDef def, WallBuildContext ctx);
}
