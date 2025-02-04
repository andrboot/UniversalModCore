package cam72cam.mod.world;

import cam72cam.mod.MinecraftClient;
import cam72cam.mod.ModCore;
import cam72cam.mod.block.BlockEntity;
import cam72cam.mod.block.BlockType;
import cam72cam.mod.block.tile.TileEntity;
import cam72cam.mod.entity.*;
import cam72cam.mod.entity.boundingbox.BoundingBox;
import cam72cam.mod.entity.boundingbox.IBoundingBox;
import cam72cam.mod.event.ClientEvents;
import cam72cam.mod.event.CommonEvents;
import cam72cam.mod.fluid.ITank;
import cam72cam.mod.item.IInventory;
import cam72cam.mod.item.ItemStack;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.util.Facing;
import cam72cam.mod.serialization.TagCompound;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Wraps both ClientWorld and ServerWorld */
public class World {

    /* Static access to loaded worlds */
    private static final Map<Integer, World> clientWorlds = new HashMap<>();
    private static final Map<Integer, World> serverWorlds = new HashMap<>();
    private static final List<Consumer<World>> onTicks = new ArrayList<>();

    /** Internal, do not use */
    public final net.minecraft.world.World internal;
    /** isClient == world.isRemote */
    public final boolean isClient;
    /** isServer != world.isRemote */
    public final boolean isServer;

    private final Map<Integer, Entity> entityByID = new HashMap<>();
    private final Map<UUID, Entity> entityByUUID = new HashMap<>();
    private final Map<Class<?>, List<Entity>> entitiesByClass = new HashMap<>();

    /* World Initialization */

    private World(net.minecraft.world.World world) {
        internal = world;
        isClient = world.isRemote;
        isServer = !world.isRemote;
    }

    /** Helper function to get a world map (client or server) */
    private static Map<Integer, World> getWorldMap(net.minecraft.world.World world) {
        return world.isRemote ? clientWorlds : serverWorlds;
    }
    /** Helper function to get a world in it's respective map */
    private static World getWorld(net.minecraft.world.World world){
        return getWorldMap(world).get(world.provider.getDimension());
    }

    /** Load world hander, sets up maps and internal handlers */
    private static void loadWorld(net.minecraft.world.World world) {
        if (getWorld(world) == null) {
            World worldWrap = new World(world);
            getWorldMap(world).put(worldWrap.getId(), worldWrap);
            world.addEventListener(new WorldEventListener(worldWrap));
        }
    }

    /** Called from Event system, wires into common world events */
    public static void registerEvents() {
        CommonEvents.World.LOAD.subscribe(World::loadWorld);

        CommonEvents.World.UNLOAD.subscribe(world -> getWorldMap(world).remove(world.provider.getDimension()));

        CommonEvents.World.TICK.subscribe(world -> onTicks.forEach(fn -> fn.accept(get(world))));

        CommonEvents.World.TICK.subscribe(world -> get(world).checkLoadedEntities());
    }

    public static void registerClientEvnets() {
        ClientEvents.TICK.subscribe(() -> {
            if (MinecraftClient.isReady()) {
                MinecraftClient.getPlayer().getWorld().checkLoadedEntities();
            }
        });
    }

    private void checkLoadedEntities() {
        // Once a second scan entities that may have de-sync'd with the UMC world
        if (this.getTicks() % 20 == 0) {
            for (net.minecraft.entity.Entity entity : this.internal.loadedEntityList) {
                if (!this.entityByID.containsKey(entity.getEntityId())) {
                    ModCore.warn("Adding entity that was not wrapped correctly %s - %s", entity.getUniqueID(), entity);
                    this.onEntityAdded(entity);
                }
            }
            for (int entityId : new ArrayList<>(this.entityByID.keySet())) {
                if (this.internal.getEntityByID(entityId) == null) {
                    Entity entity = this.entityByID.get(entityId);
                    if (entity != null && !this.internal.loadedEntityList.contains(entity.internal)) {
                        ModCore.warn("Dropping entity that was not removed correctly %s - %s", entity.getUUID(), entity);
                        this.onEntityRemoved(entity.internal);
                    }
                }
            }
        }
    }

    /** Turn a MC world into a UMC world */
    public static World get(net.minecraft.world.World world) {
        if (world == null) {
            return null;
        }
        if (getWorld(world) == null) {
            // WTF forge
            // I should NOT need to do this
            loadWorld(world);
        }

        return getWorld(world);
    }

    /** Based on dim/isRemote get the corresponding UMC world.  Not recommended for general use. */
    public static World get(int dimID, boolean isClient) {
        return (isClient ? clientWorlds : serverWorlds).get(dimID);
    }

    /** Add tick handler */
    public static void onTick(Consumer<World> fn) {
        onTicks.add(fn);
    }

    /** World's internal ID, Not recommended for general use. */
    public int getId() {
        return internal.provider.getDimension();
    }

    /* Event Methods */

    /**
     * Handle tracking entities that have been added to the internal world.
     * Wiring from WorldEventListener
     */
    void onEntityAdded(net.minecraft.entity.Entity entityIn) {
        if (entityByID.containsKey(entityIn.getEntityId())) {
            // Dupe
            return;
        }

        Entity entity;
        if (entityIn instanceof ModdedEntity) {
            entity = ((ModdedEntity) entityIn).getSelf();
        } else if (entityIn instanceof EntityPlayer) {
            entity = new Player((EntityPlayer) entityIn);
        } else if (entityIn instanceof EntityLiving) {
            entity = new Living((EntityLiving) entityIn);
        } else {
            entity = new Entity(entityIn);
        }
        entitiesByClass.putIfAbsent(entity.getClass(), new ArrayList<>());
        entitiesByClass.get(entity.getClass()).add(entity);
        entityByID.put(entityIn.getEntityId(), entity);
        entityByUUID.put(entity.getUUID(), entity);
    }

    /**
     * Handle tracking entities that have been removed from the internal world.
     * Wiring from WorldEventListener
     */
    void onEntityRemoved(net.minecraft.entity.Entity entity) {
        for (List<Entity> value : entitiesByClass.values()) {
            value.removeIf(inner -> inner.getUUID().equals(entity.getUniqueID()));
        }
        entityByID.remove(entity.getEntityId());
        entityByUUID.remove(entity.getUniqueID());
    }

    /* Entity Methods */

    /** Find a UMC entity by MC entity */
    public Entity getEntity(net.minecraft.entity.Entity entity) {
        return getEntity(entity.getUniqueID(), Entity.class);
    }

    /** Find a UMC entity by MC ID and Entity class */
    public <T extends Entity> T getEntity(int id, Class<T> type) {
        Entity ent = entityByID.get(id);
        if (ent == null) {
            return null;
        }
        if (!type.isInstance(ent)) {
            ModCore.warn("When looking for entity %s by id %s, we instead got a %s", type, id, ent.getClass());
            return null;
        }
        return (T) ent;
    }

    /** Find UMC entity by MC Entity, assuming type */
    public <T extends Entity> T getEntity(UUID id, Class<T> type) {
        Entity ent = entityByUUID.get(id);
        if (ent == null) {
            return null;
        }
        if (!type.isInstance(ent)) {
            ModCore.warn("When looking for entity %s by id %s, we instead got a %s", type, id, ent.getClass());
            return null;
        }
        return (T) ent;
    }

    /** Find UMC entities by type */
    public <T extends Entity> List<T> getEntities(Class<T> type) {
        return getEntities((T val) -> true, type);
    }

    /** Find UMC Entities which match the filter and are of the given type */
    public <T extends Entity> List<T> getEntities(Predicate<T> filter, Class<T> type) {
        List<T> list = new ArrayList<>();
        for (Class<?> key : entitiesByClass.keySet()) {
            if (type.isAssignableFrom(key)) {
                for (Entity entity : entitiesByClass.get(key)) {
                    T as = entity.as(type);
                    if (as != null) {
                        if (filter.test(as)) {
                            list.add(as);
                        }
                    }
                }
            }
        }
        return list;
    }

    /** Add a constructed entity to the world */
    public boolean spawnEntity(Entity ent) {
        return internal.spawnEntity(ent.internal);
    }

    /** Kill an entity */
    public void removeEntity(Entity entity) {
        internal.removeEntity(entity.internal);
    }

    /** Force a chunk for up to 5s */
    public void keepLoaded(Vec3i pos) {
        ChunkManager.flagEntityPos(this, pos);
    }

    /** Internal, do not use */
    public <T extends net.minecraft.tileentity.TileEntity> T getTileEntity(Vec3i pos, Class<T> cls) {
        return getTileEntity(pos, cls, true);
    }

    /** Internal, do not use */
    public <T extends net.minecraft.tileentity.TileEntity> T getTileEntity(Vec3i pos, Class<T> cls, boolean create) {
        net.minecraft.tileentity.TileEntity ent = internal.getChunk(pos.internal()).getTileEntity(pos.internal(), create ? Chunk.EnumCreateEntityType.IMMEDIATE : Chunk.EnumCreateEntityType.CHECK);
        if (cls.isInstance(ent)) {
            return (T) ent;
        }
        return null;
    }

    /** Get all block entities of the given type */
    public <T extends BlockEntity> List<T> getBlockEntities(Class<T> cls) {
        return internal.loadedTileEntityList.stream()
                .filter(x -> x instanceof TileEntity && ((TileEntity) x).isLoaded() && cls.isInstance(((TileEntity) x).instance()))
                .map(x -> (T) ((TileEntity) x).instance())
                .collect(Collectors.toList());
    }

    /** Get a block entity at the position, assuming type */
    public <T extends BlockEntity> T getBlockEntity(Vec3i pos, Class<T> cls) {
        TileEntity te = getTileEntity(pos, TileEntity.class);
        if (te == null) {
            return null;
        }
        BlockEntity instance = te.instance();
        if (cls.isInstance(instance)) {
            return (T) instance;
        }
        return null;
    }

    /** Does this block have a block entity of the given type? */
    public <T extends BlockEntity> boolean hasBlockEntity(Vec3i pos, Class<T> cls) {
        TileEntity te = getTileEntity(pos, TileEntity.class);
        if (te == null) {
            return false;
        }
        return cls.isInstance(te.instance());
    }

    /**
     * Turn the given data back into a block
     *
     * @see BlockEntity#getData
     */
    public BlockEntity reconstituteBlockEntity(TagCompound data) {
        TileEntity te = (TileEntity) TileEntity.create(internal, data.internal);
        if (te == null) {
            ModCore.warn("BAD TE DATA " + data);
            return null;
        }
        if (te.instance() == null) {
            ModCore.warn("Loaded " + te.isLoaded() + " " + data);
        }
        return te.instance();
    }

    /** Set the block entity at pos to given entity */
    public void setBlockEntity(Vec3i pos, BlockEntity entity) {
        internal.setTileEntity(pos.internal(), entity != null ? entity.internal : null);
        if (entity != null) {
            entity.markDirty();
        }
    }

    /** World time in ticks (Day/Night cycle time)*/
    public long getTime() {
        return internal.getWorldTime();
    }

    /** Time since world was originally created (updated when loaded) */
    public long getTicks() {
        return internal.getTotalWorldTime();
    }

    /** Ticks per second (with up to N samples) */
    public double getTPS(int sampleSize) {
        if (internal.getMinecraftServer() == null) {
            return 20;
        }

        long[] ttl = internal.getMinecraftServer().tickTimeArray;

        sampleSize = Math.min(sampleSize, ttl.length);
        double ttus = 0;
        for (int i = 0; i < sampleSize; i++) {
            ttus += ttl[ttl.length - 1 - i] / (double) sampleSize;
        }

        if (ttus == 0) {
            ttus = 0.01;
        }

        double ttms = ttus * 1.0E-6D;
        return Math.min(1000.0 / ttms, 20);
    }

    /** Height of the ground for precipitation purposes at the given block */
    public Vec3i getPrecipitationHeight(Vec3i pos) {
        return new Vec3i(internal.getPrecipitationHeight(pos.internal()));
    }

    /** Set the given pos to air */
    public void setToAir(Vec3i pos) {
        internal.setBlockToAir(pos.internal());
    }

    /** If the block at pos is air */
    public boolean isAir(Vec3i ph) {
        return internal.isAirBlock(ph.internal());
    }

    /** Set the snow level to the given depth (1-8) */
    public void setSnowLevel(Vec3i ph, int snowDown) {
        snowDown = Math.max(1, Math.min(8, snowDown));
        if (snowDown == 8) {
            internal.setBlockState(ph.internal(), Blocks.SNOW.getDefaultState());
        } else {
            internal.setBlockState(ph.internal(), Blocks.SNOW_LAYER.getDefaultState().withProperty(BlockSnow.LAYERS, snowDown));
        }
    }

    /** Get the snow level (1-8) */
    public int getSnowLevel(Vec3i ph) {
        IBlockState state = internal.getBlockState(ph.internal());
        if (state.getBlock() == Blocks.SNOW_LAYER) {
            return state.getValue(BlockSnow.LAYERS);
        }
        if (state.getBlock() == Blocks.SNOW) {
            return 8;
        }
        return 0;
    }

    /** If this block is snow or snow layers */
    public boolean isSnow(Vec3i ph) {
        Block block = internal.getBlockState(ph.internal()).getBlock();
        return block == Blocks.SNOW || block == Blocks.SNOW_LAYER;
    }

    /** If it is snowing or raining */
    public boolean isPrecipitating() {
        return internal.isRaining();
    }

    /** If it is is raining */
    public boolean isRaining(Vec3i position) {
        return isPrecipitating() && internal.getBiome(position.internal()).canRain();
    }

    /** If it is snowing */
    public boolean isSnowing(Vec3i position) {
        return isPrecipitating() && internal.getBiome(position.internal()).isSnowyBiome();
    }

    /** Temp in celsius */
    public float getTemperature(Vec3i pos) {
        float mctemp = internal.getBiome(pos.internal()).getTemperature(pos.internal());
        //https://www.reddit.com/r/Minecraft/comments/3eh7yu/the_rl_temperature_of_minecraft_biomes_revealed/ctex050/
        return (13.6484805403f * mctemp) + 7.0879687222f;
    }

    /** Drop a stack on the ground at pos */
    public void dropItem(ItemStack stack, Vec3i pos) {
        dropItem(stack, new Vec3d(pos));
    }

    /** Drop a stack on the ground at pos */
    public void dropItem(ItemStack stack, Vec3d pos) {
        internal.spawnEntity(new EntityItem(internal, pos.x, pos.y, pos.z, stack.internal));
    }

    /** Check if the block is currently in a loaded chunk */
    public boolean isBlockLoaded(Vec3i parent) {
        return internal.isBlockLoaded(parent.internal());
    }

    /** Check if block at pos collides with a BB */
    public boolean doesBlockCollideWith(Vec3i bp, IBoundingBox bb) {
        AxisAlignedBB cbb = internal.getBlockState(bp.internal()).getCollisionBoundingBox(internal, bp.internal());
        if (cbb == null) {
           return false;
        }
        return bb.intersects(IBoundingBox.from(cbb.offset(bp.internal())));
    }

    public List<Vec3i> blocksInBounds(IBoundingBox bb) {
        return internal.getCollisionBoxes(null, BoundingBox.from(bb)).stream()
                .map(blockBox -> new Vec3i(blockBox.minX, blockBox.minY, blockBox.minZ))
                .collect(Collectors.toList());
    }

    /** Break block (with in-world drops) */
    public void breakBlock(Vec3i pos) {
        this.breakBlock(pos, true);
    }

    /** Break block with sound effecnts, particles and optional drops */
    public void breakBlock(Vec3i pos, boolean drop) {
        internal.destroyBlock(pos.internal(), drop);
    }

    /** If block is the given type */
    public boolean isBlock(Vec3i pos, BlockType block) {
        return internal.getBlockState(pos.internal()).getBlock() == block.internal;
    }

    /** Set block to a given block type */
    public void setBlock(Vec3i pos, BlockType block) {
        internal.setBlockState(pos.internal(), block.internal.getDefaultState());
    }

    /** Set a block to given stack (best guestimate) */
    public void setBlock(Vec3i pos, ItemStack stack) {
        IBlockState state = Block.getBlockFromItem(stack.internal.getItem()).getStateFromMeta(stack.internal.getMetadata());
        internal.setBlockState(pos.internal(), state);
    }

    /** Is the top of the block solid?  Based on some AABB nonsense */
    public boolean isTopSolid(Vec3i pos) {
        return internal.getBlockState(pos.internal()).isTopSolid();
    }

    /** How hard is the block? */
    public float getBlockHardness(Vec3i pos) {
        return internal.getBlockState(pos.internal()).getBlockHardness(internal, pos.internal());
    }

    /** Get max redstone power surrounding this block */
    public int getRedstone(Vec3i pos) {
        int power = 0;
        for (Facing facing : Facing.values()) {
            power = Math.max(power, internal.getRedstonePower(pos.offset(facing).internal(), facing.internal));
        }
        return power;
    }

    /** If the sky is visible at this position */
    public boolean canSeeSky(Vec3i position) {
        return internal.canSeeSky(position.internal());
    }

    /**
     * Some generic rules for if a block is replaceable
     *
     * This mainly relies on Block.isReplaceable, but not all mod authors hook into it correctly.
     */
    public boolean isReplaceable(Vec3i pos) {
        if (isAir(pos)) {
            return true;
        }

        Block block = internal.getBlockState(pos.internal()).getBlock();

        if (block.isReplaceable(internal, pos.internal())) {
            return true;
        }
        if (block instanceof IGrowable && !(block instanceof BlockGrass)) {
            return true;
        }
        if (block instanceof IPlantable) {
            return true;
        }
        if (block instanceof BlockLiquid) {
            return true;
        }
        if (block instanceof BlockSnow) {
            return true;
        }
        if (block instanceof BlockLeaves) {
            return true;
        }
        return false;
    }

    /* Capabilities */

    /** Get the inventory at this block (accessed from any side) */
    public IInventory getInventory(Vec3i offset) {
        for (Facing value : Facing.values()) {
            IInventory inv = getInventory(offset, value);
            if (inv != null) {
                return inv;
            }
        }
        return getInventory(offset, null);
    }

    /** Get the inventory at this block (accessed from given side) */
    public IInventory getInventory(Vec3i offset, Facing dir) {
        net.minecraft.tileentity.TileEntity te = internal.getTileEntity(offset.internal());
        EnumFacing face = dir != null ? dir.internal : null;
        if (te != null && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face)) {
            IItemHandler inv = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face);
            if (inv instanceof IItemHandlerModifiable) {
                return IInventory.from((IItemHandlerModifiable) inv);
            }
        }
        return null;
    }

    /** Get the tank at this block (accessed from any side) */
    public List<ITank> getTank(Vec3i offset) {
        for (Facing value : Facing.values()) {
            List<ITank> tank = getTank(offset, value);
            if (tank != null) {
                return tank;
            }
        }
        return getTank(offset, null);
    }

    /** Get the tank at this block (accessed from given side) */
    public List<ITank> getTank(Vec3i offset, Facing dir) {
        net.minecraft.tileentity.TileEntity te = internal.getTileEntity(offset.internal());
        EnumFacing face = dir != null ? dir.internal : null;
        if (te != null && te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face)) {
            IFluidHandler tank = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face);
            if (tank != null) {
                return ITank.getTank(tank);
            }
        }
        return null;
    }

    /** Get stack equiv of block at pos (Unreliable!) */
    public ItemStack getItemStack(Vec3i pos) {
        IBlockState state = internal.getBlockState(pos.internal());
        try {
            return new ItemStack(state.getBlock().getItem(internal, pos.internal(), state));
        } catch (Exception ex) {
            return new ItemStack(new net.minecraft.item.ItemStack(state.getBlock(), 1, state.getBlock().damageDropped(state)));
        }
    }

    /** Get dropped items within the given area */
    public List<ItemStack> getDroppedItems(IBoundingBox bb) {
        List<EntityItem> items = internal.getEntitiesWithinAABB(EntityItem.class, BoundingBox.from(bb));
        return items.stream().map((EntityItem::getItem)).map(ItemStack::new).collect(Collectors.toList());
    }

    /** Get a BlockInfo that can be used to overwrite a block in the future.  Does not currently include TE data */
    public BlockInfo getBlock(Vec3i pos) {
        return new BlockInfo(internal.getBlockState(pos.internal()));
    }

    /** Overwrite the block at pos from the given info */
    public void setBlock(Vec3i pos, BlockInfo info) {
        internal.removeTileEntity(pos.internal());
        internal.setBlockState(pos.internal(), info.internal);
    }

    /** Opt in collision overriding */
    public boolean canEntityCollideWith(Vec3i bp, String damageType) {
        Block block = internal.getBlockState(bp.internal()).getBlock();
        return ! (block instanceof IConditionalCollision) ||
                ((IConditionalCollision) block).canCollide(internal, bp.internal(), internal.getBlockState(bp.internal()), new DamageSource(damageType));
    }

    /** Spawn a particle */
    public void createParticle(ParticleType type, Vec3d position, Vec3d velocity) {
        internal.spawnParticle(type.internal, position.x, position.y, position.z, velocity.x, velocity.y, velocity.z);
    }

    /**
     *
     * Updates the blocks around the position.
     * Value updateObservers will be ignored in some MC versions.
     *
     * @param pos
     * @param blockType
     * @param updateObservers
     */
    public void notifyNeighborsOfStateChange(Vec3i pos, BlockType blockType, boolean updateObservers){
        this.internal.notifyNeighborsOfStateChange(pos.internal(), blockType.internal, updateObservers);
    }

    public enum ParticleType {
        SMOKE(EnumParticleTypes.SMOKE_NORMAL),
        // Incomplete
        ;

        private final EnumParticleTypes internal;

        ParticleType(EnumParticleTypes internal) {
            this.internal = internal;
        }
    }

    public float getBlockLightLevel(Vec3i pos) {
        return internal.getLightFor(EnumSkyBlock.BLOCK, pos.internal()) / 15f;
    }

    public float getSkyLightLevel(Vec3i pos) {
        return internal.getLightFor(EnumSkyBlock.SKY, pos.internal()) / 15f;
    }
}
