package mekanism.cloudworks.api.module;

/**
 * The two upgrade axes a node-level module may take over from the line effect
 * (axis-priority plan §4). Taking an axis means the module's own effective
 * count replaces the line coverage for that axis — never multiplies it.
 */
public enum UpgradeAxis { SPEED, ENERGY }
