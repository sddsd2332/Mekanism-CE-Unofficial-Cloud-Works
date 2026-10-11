package mekanism.cloudworks.common.content;

import java.util.UUID;

/**
 * Owner binding for placed cloudworks port blocks. The placing player becomes the
 * transfer owner once and durably; the binding is written by
 * {@link mekanism.cloudworks.common.block.BlockCloudworksPort#onBlockPlacedBy}
 * and persisted by the tile.
 */
public interface CloudworksOwnerPort {
    void bindOwner(UUID owner);
}
