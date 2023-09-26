package me.jellysquid.mods.sodium.mixin.features.render.particle;

import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import me.jellysquid.mods.sodium.client.render.particle.ParticleExtended;
import me.jellysquid.mods.sodium.client.render.particle.ParticleRenderView;
import me.jellysquid.mods.sodium.client.render.particle.ShaderBillboardParticleRenderer;
import me.jellysquid.mods.sodium.client.render.particle.shader.BillboardParticleVertex;
import me.jellysquid.mods.sodium.client.render.particle.shader.ParticleShaderInterface;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.particle.*;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Unique
    private final BufferBuilder bufferBuilder = new BufferBuilder(1);

    @Unique
    private final BufferBuilder testBuffer = new BufferBuilder(1);

    @Shadow
    protected ClientWorld world;

    @Shadow
    @Final
    private static List<ParticleTextureSheet> PARTICLE_TEXTURE_SHEETS;

    @Shadow
    @Final
    private Queue<EmitterParticle> newEmitterParticles;

    @Shadow
    @Final
    private Queue<Particle> newParticles;

    @Shadow
    @Final
    private Map<ParticleTextureSheet, Queue<Particle>> particles;

    @Unique
    private final Map<ParticleTextureSheet, Queue<BillboardParticle>> billboardParticles = Maps.newIdentityHashMap();

    @Unique
    private final ShaderBillboardParticleRenderer particleRenderer = new ShaderBillboardParticleRenderer();

    @Unique
    private static final Object2BooleanMap<Class<? extends BillboardParticle>> classOverridesBuild = new Object2BooleanOpenHashMap<>();

    @Unique
    private ParticleRenderView renderView;

    @Unique
    private static final String BUILD_GEOMETRY_METHOD = FabricLoader.getInstance().getMappingResolver().mapMethodName(
            "intermediary",
            "net.minecraft.class_703",
            "method_3074",
            "(Lnet/minecraft/class_4588;Lnet/minecraft/class_4184;F)V"
    );

    @Unique
    private int bufferSize = 0;

    @Unique
    private int glVertexBuffer;

    @Unique
    private int glVertexArray;

    @Unique
    private RenderSystem.ShapeIndexBuffer sharedSequentialIndexBuffer;

    @Unique
    private Identifier prevTexture = null;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void postInit(ClientWorld world, TextureManager textureManager, CallbackInfo ci) {
        this.glVertexBuffer = GlStateManager._glGenBuffers();
        this.glVertexArray = GlStateManager._glGenVertexArrays();
        this.renderView = new ParticleRenderView(world);
    }

    @Shadow
    protected abstract void tickParticles(Collection<Particle> particles);

    /**
     * @author BeljihnWahfl
     * @reason Could not feasibly inject all needed functionality
     */
    @Overwrite
    public void tick() {
        testBuffer.begin(VertexFormat.DrawMode.QUADS, BillboardParticleVertex.MC_VERTEX_FORMAT);
        this.particles.forEach((sheet, queue) -> {
            this.world.getProfiler().push(sheet.toString());
            this.tickParticles(queue);
            this.world.getProfiler().pop();
        });

        this.billboardParticles.forEach((sheet, queue) -> {
            this.world.getProfiler().push(sheet.toString());
            // This is safe because tickParticles never adds to the collection.
            this.tickParticles((Collection) queue);
            this.world.getProfiler().pop();
        });

        if (!this.newEmitterParticles.isEmpty()) {
            List<EmitterParticle> list = Lists.newArrayList();

            for(EmitterParticle emitterParticle : this.newEmitterParticles) {
                emitterParticle.tick();
                if (!emitterParticle.isAlive()) {
                    list.add(emitterParticle);
                }
            }

            this.newEmitterParticles.removeAll(list);
        }

        Particle particle;
        if (!this.newParticles.isEmpty()) {
            while((particle = this.newParticles.poll()) != null) {
                if (particle instanceof BillboardParticle bParticle && !classOverridesBuild.computeIfAbsent(
                        bParticle.getClass(),
                        this::testClassOverrides
                )) {
                    this.billboardParticles
                            .computeIfAbsent(particle.getType(), sheet -> EvictingQueue.create(16384))
                            .add((BillboardParticle) particle);
                } else {
                    this.particles
                            .computeIfAbsent(particle.getType(), sheet -> EvictingQueue.create(16384))
                            .add(particle);
                }
            }
        }

        testBuffer.end().release();
    }

    @Unique
    private boolean testClassOverrides(Class<? extends BillboardParticle> particleClass) {
        try {
            return particleClass.getDeclaredMethod(
                    BUILD_GEOMETRY_METHOD,
                    VertexConsumer.class,
                    Camera.class,
                    float.class
            ).getDeclaringClass() != BillboardParticle.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Inject(method = "clearParticles", at = @At("TAIL"))
    private void clearParticles(CallbackInfo ci) {
        this.billboardParticles.clear();
    }

    @Inject(method = "renderParticles", at = @At("HEAD"))
    private void preRenderParticles(MatrixStack matrices,
                                    VertexConsumerProvider.Immediate vertexConsumers,
                                    LightmapTextureManager lightmapTextureManager,
                                    Camera camera,
                                    float tickDelta,
                                    CallbackInfo ci) {
        this.renderView.resetCache();
    }

    @Inject(method = "renderParticles", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;applyModelViewMatrix()V", ordinal = 0, shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
    public void renderParticles(
            MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers,
            LightmapTextureManager lightmapTextureManager, Camera camera, float tickDelta,
            CallbackInfo ci, MatrixStack matrixStack
    ) {
        particleRenderer.begin();
        ParticleShaderInterface shader = this.particleRenderer.getActiveProgram().getInterface();
        shader.setProjectionMatrix(RenderSystem.getProjectionMatrix());
        shader.setModelViewMatrix(RenderSystem.getModelViewMatrix());

        for (ParticleTextureSheet particleTextureSheet : PARTICLE_TEXTURE_SHEETS) {
            Queue<BillboardParticle> iterable = this.billboardParticles.get(particleTextureSheet);
            if (iterable != null && !iterable.isEmpty()) {
                int numParticles = iterable.size();
                bindParticleTextureSheet(particleTextureSheet);
                particleRenderer.setupState();
                bufferBuilder.begin(VertexFormat.DrawMode.QUADS, BillboardParticleVertex.MC_VERTEX_FORMAT);

                for (BillboardParticle particle : iterable) {
                    particle.buildGeometry(bufferBuilder, camera, tickDelta);
                }

                drawParticleTextureSheet(particleTextureSheet, bufferBuilder, numParticles);
                bufferBuilder.clear();
            }
        }

        prevTexture = null;
        particleRenderer.end();
    }

    @Unique
    private void bindParticleTextureSheet(ParticleTextureSheet sheet) {
        RenderSystem.depthMask(true);
        Identifier texture = null;
        if (sheet == ParticleTextureSheet.PARTICLE_SHEET_LIT || sheet == ParticleTextureSheet.PARTICLE_SHEET_OPAQUE) {
            RenderSystem.disableBlend();
            texture = SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE;
        } else if (sheet == ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT) {
            RenderSystem.enableBlend();
            texture = SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE;
        } else if (sheet == ParticleTextureSheet.TERRAIN_SHEET) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            texture = SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
        } else if (sheet == ParticleTextureSheet.CUSTOM) {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }

        if (texture != null && !texture.equals(prevTexture)) {
            RenderSystem.setShaderTexture(0, texture);
            this.prevTexture = texture;
        }
    }

    @Unique
    private void drawParticleTextureSheet(ParticleTextureSheet sheet, BufferBuilder builder, int numParticles) {
        if (sheet == ParticleTextureSheet.TERRAIN_SHEET || sheet == ParticleTextureSheet.PARTICLE_SHEET_LIT || sheet == ParticleTextureSheet.PARTICLE_SHEET_OPAQUE || sheet == ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT) {
            BufferBuilder.BuiltBuffer built = builder.end();
            BufferBuilder.DrawParameters parameters = built.getParameters();
            ByteBuffer vertexBuffer = built.getVertexBuffer();
            int neededSize = parameters.vertexCount() * BillboardParticleVertex.STRIDE;

            GlStateManager._glBindVertexArray(this.glVertexArray);
            GlStateManager._glBindBuffer(GlConst.GL_ARRAY_BUFFER, this.glVertexBuffer);
            if (neededSize > this.bufferSize) {
                RenderSystem.glBufferData(GlConst.GL_ARRAY_BUFFER, vertexBuffer, GlConst.GL_DYNAMIC_DRAW);
                this.bufferSize = neededSize;
            } else {
                GL20.glBufferSubData(GlConst.GL_ARRAY_BUFFER, 0, vertexBuffer);
            }

            BillboardParticleVertex.bindVertexFormat();
            uploadIndexBuffer(parameters);
            int indexType = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS).getIndexType().glType;
            RenderSystem.drawElements(VertexFormat.DrawMode.QUADS.glMode, parameters.indexCount(), indexType);
            built.release();
        }
    }

    @Unique
    private void uploadIndexBuffer(BufferBuilder.DrawParameters parameters) {
        RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(parameters.mode());
        if (shapeIndexBuffer != this.sharedSequentialIndexBuffer || !shapeIndexBuffer.isLargeEnough(parameters.indexCount())) {
            shapeIndexBuffer.bindAndGrow(parameters.indexCount());
            this.sharedSequentialIndexBuffer = shapeIndexBuffer;
        }
    }

    @Inject(method = "setWorld", at = @At("RETURN"))
    private void postSetWorld(ClientWorld world, CallbackInfo ci) {
        this.renderView = new ParticleRenderView(world);
    }

    @Inject(method = "createParticle", at = @At("RETURN"))
    private <T extends ParticleEffect> void postCreateParticle(T parameters,
                                                               double x,
                                                               double y,
                                                               double z,
                                                               double velocityX,
                                                               double velocityY,
                                                               double velocityZ,
                                                               CallbackInfoReturnable<@Nullable Particle> cir) {
        var particle = cir.getReturnValue();

        if (particle instanceof ParticleExtended extension) {
            extension.sodium$configure(this.renderView);
        }
    }
}