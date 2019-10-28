package cam72cam.mod.block.tile;

import cam72cam.mod.ModCore;
import cam72cam.mod.block.BlockEntity;
import cam72cam.mod.energy.IEnergy;
import cam72cam.mod.entity.boundingbox.BoundingBox;
import cam72cam.mod.entity.boundingbox.IBoundingBox;
import cam72cam.mod.fluid.Fluid;
import cam72cam.mod.fluid.ITank;
import cam72cam.mod.item.IInventory;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.resource.Identifier;
import cam72cam.mod.util.Facing;
import cam72cam.mod.util.TagCompound;
import cam72cam.mod.world.World;
import com.google.common.collect.HashBiMap;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;

public class TileEntity extends net.minecraft.tileentity.TileEntity {
    private static final Map<String, Supplier<BlockEntity>> registry = HashBiMap.create();
    public boolean hasTileData;
    private String instanceId;

    /*
    Tile registration
    */
    private BlockEntity instance;
    private TagCompound deferredLoad;

    public TileEntity() {
        // Forge reflection
        super();
    }

    public TileEntity(Identifier id) {
        this();
        instanceId = id.toString();
    }

    public static void register(Supplier<BlockEntity> instance, Identifier id) {
        registry.put(id.toString(), instance);
    }

    public final void register() {
        try {
            TileEntity.addMapping(this.getClass(), this.getName().internal.toString());
        } catch (IllegalArgumentException ex) {
            //pass
        }
    }

    public Identifier getName() {
        return new Identifier(ModCore.MODID, "hack");
    }


    /*
    Standard Tile function overrides
    */

    @Override
    public final void readFromNBT(NBTTagCompound compound) {
        hasTileData = true;
        load(new TagCompound(compound));
    }

    @Override
    public final void writeToNBT(NBTTagCompound compound) {
        save(new TagCompound(compound));
    }

    @Override
    public Packet getDescriptionPacket() {
        TagCompound nbt = new TagCompound();
        this.writeToNBT(nbt.internal);
        this.writeUpdate(nbt);

        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 6, nbt.internal);
    }

    @Override
    public final void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        hasTileData = true;
        this.readFromNBT(pkt.func_148857_g());
        this.readUpdate(new TagCompound(pkt.func_148857_g()));
        super.onDataPacket(net, pkt);
        worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord,xCoord, yCoord, zCoord);
    }

    @Override
    public void markDirty() {
        super.markDirty();
        if (!worldObj.isRemote) {
            worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, blockType, 1 + 2 + 8);
            //TODO 1.7.10? world.internal.notifyNeighborsOfStateChange(pos.internal, this.getBlockType());
        }
    }

    /* Forge Overrides */

    public net.minecraft.util.AxisAlignedBB getRenderBoundingBox() {
        if (instance() != null) {
            IBoundingBox bb = instance().getBoundingBox();
            if (bb != null) {
                return new BoundingBox(bb);
            }
        }
        return INFINITE_EXTENT_AABB;
    }

    public double getMaxRenderDistanceSquared() {
        return instance() != null ? instance().getRenderDistance() * instance().getRenderDistance() : Integer.MAX_VALUE;
    }

    /* TODO 1.7.10 CAPABILITIES
    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, @Nullable net.minecraft.util.EnumFacing facing) {
        //TODO more efficient
        return getCapability(capability, facing) != null;
    }

    @Override
    @Nullable
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, @Nullable net.minecraft.util.EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            ITank target = getTank(Facing.from(facing));
            if (target == null) {
                return null;
            }
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(new IFluidHandler() {
                @Override
                public IFluidTankProperties[] getTankProperties() {
                    return new IFluidTankProperties[]{
                            new IFluidTankProperties() {
                                @Nullable
                                @Override
                                public FluidStack getContents() {
                                    return target.getContents().internal;
                                }

                                @Override
                                public int getCapacity() {
                                    return target.getCapacity();
                                }

                                @Override
                                public boolean canFill() {
                                    return true;
                                }

                                @Override
                                public boolean canDrain() {
                                    return true;
                                }

                                @Override
                                public boolean canFillFluidType(FluidStack fluidStack) {
                                    return target.allows(Fluid.getFluid(fluidStack.getFluid()));
                                }

                                @Override
                                public boolean canDrainFluidType(FluidStack fluidStack) {
                                    return target.allows(Fluid.getFluid(fluidStack.getFluid()));
                                }
                            }
                    };
                }

                @Override
                public int fill(FluidStack resource, boolean doFill) {
                    int res = target.fill(new cam72cam.mod.fluid.FluidStack(resource), !doFill);
                    return res;
                }

                @Nullable
                @Override
                public FluidStack drain(FluidStack resource, boolean doDrain) {
                    return target.drain(new cam72cam.mod.fluid.FluidStack(resource), !doDrain).internal;
                }

                @Nullable
                @Override
                public FluidStack drain(int maxDrain, boolean doDrain) {
                    if (target.getContents().internal == null) {
                        return null;
                    }
                    return target.drain(new cam72cam.mod.fluid.FluidStack(new FluidStack(target.getContents().internal, maxDrain)), doDrain).internal;
                }
            });
        }
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            IInventory target = getInventory(Facing.from(facing));
            if (target == null) {
                return null;
            }
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(new ItemStackHandler(target.getSlotCount()) {
                @Override
                public int getSlots() {
                    return target.getSlotCount();
                }

                @Override
                public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
                    target.set(slot, new cam72cam.mod.item.ItemStack(stack));
                }

                @Nonnull
                @Override
                public ItemStack getStackInSlot(int slot) {
                    return target.get(slot).internal;
                }

                @Nonnull
                @Override
                public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
                    return target.insert(slot, new cam72cam.mod.item.ItemStack(stack), simulate).internal;
                }

                @Nonnull
                @Override
                public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    return target.extract(slot, amount, simulate).internal;
                }

                @Override
                protected int getStackLimit(int slot, ItemStack stack)
                {
                    return target.getLimit(slot);
                }

            });
        }
        if (capability == CapabilityEnergy.ENERGY) {
            IEnergy target = getEnergy(Facing.from(facing));
            if (target == null) {
                return null;
            }
            return CapabilityEnergy.ENERGY.cast(new IEnergyStorage() {
                @Override
                public int receiveEnergy(int maxReceive, boolean simulate) {
                    return target.receive(maxReceive, simulate);
                }

                @Override
                public int extractEnergy(int maxExtract, boolean simulate) {
                    return target.extract(maxExtract, simulate);
                }

                @Override
                public int getEnergyStored() {
                    return target.getCurrent();
                }

                @Override
                public int getMaxEnergyStored() {
                    return target.getMax();
                }

                @Override
                public boolean canExtract() {
                    return true;
                }

                @Override
                public boolean canReceive() {
                    return true;
                }
            });
        }
        return null;
    }
    */

    /*
    Wrapped functionality
    */

    public void setWorld(World world) {
        super.setWorldObj(world.internal);
    }

    public void load(TagCompound data) {
        super.readFromNBT(data.internal);

        if (instanceId == null) {
            // If this fails something is really wrong
            instanceId = data.getString("instanceId");
            if (instanceId == null) {
                throw new RuntimeException("Unable to load instanceid with " + data.toString());
            }
        }

        if (instance() != null) {
            instance().load(data);
        } else {
            deferredLoad = data;
        }
    }

    public void save(TagCompound data) {
        super.writeToNBT(data.internal);
        data.setString("instanceId", instanceId);
        if (instance() != null) {
            instance().save(data);
        }
    }

    public void writeUpdate(TagCompound nbt) {
        if (instance() != null) {
            instance().writeUpdate(nbt);
        }
    }

    public void readUpdate(TagCompound nbt) {
        if (instance() != null) {
            instance().readUpdate(nbt);
        }
    }

    /*
    New Functionality
    */

    public boolean isLoaded() {
        return this.hasWorldObj() && (!worldObj.isRemote || hasTileData);
    }

    public BlockEntity instance() {
        if (this.instance == null) {
            if (isLoaded()) {
                if (this.instanceId == null) {
                    System.out.println("WAT NULL");
                }
                if (!registry.containsKey(instanceId)) {
                    System.out.println("WAT " + instanceId);
                }
                this.instance = registry.get(this.instanceId).get();
                this.instance.internal = this;
                this.instance.world = World.get(worldObj);
                this.instance.pos = new Vec3i(xCoord, yCoord, zCoord);
                if (deferredLoad != null) {
                    this.instance.load(deferredLoad);
                }
                this.deferredLoad = null;
            }
        }
        return this.instance;
    }

    /* Capabilities */

    public IInventory getInventory(Facing side) {
        return instance() != null ? instance().getInventory(side) : null;
    }

    public ITank getTank(Facing side) {
        return instance() != null ? instance().getTank(side) : null;
    }

    public IEnergy getEnergy(Facing side) {
        return instance() != null ? instance().getEnergy(side) : null;
    }
}
