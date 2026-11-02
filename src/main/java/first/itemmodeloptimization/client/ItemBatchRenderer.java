package first.itemmodeloptimization.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import first.itemmodeloptimization.ItemModelOptimization;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.opengl.GL33.*;

public final class ItemBatchRenderer {
    private final Logger logger = LoggerFactory.getLogger("ItemModelOptimization");
    private final Int2ObjectOpenHashMap<List<GpuItemBatch>> buckets = new Int2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ItemStack, StackCache> stacks = new Reference2ObjectOpenHashMap<>();
    private final IdentityHashMap<Class<?>, Boolean> dynamicFoil = new IdentityHashMap<>();
    private final Set<BakedModel> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<SpriteContents, Boolean> opaqueSprites = new IdentityHashMap<>();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f transform = new Matrix4f();
    private final BlockPos.MutableBlockPos shadowPosition = new BlockPos.MutableBlockPos();
    private final Long2IntOpenHashMap lightCells = new Long2IntOpenHashMap();
    private final List<Entity> remainingEntities = new ArrayList<>();
    private final List<ItemEntity> sceneItems = new ArrayList<>();
    private final List<Entity> remainingView = Collections.unmodifiableList(remainingEntities);
    private float partialTick;
    public boolean collecting;
    public int collected;
    public volatile boolean sceneDirty = true;
    private boolean sceneValid;
    private boolean sceneStationary;
    private boolean sceneReused;
    private long sceneTick;
    private int sceneSkyDarken;
    private int sceneEntityCount;
    private int sceneInstances;
    private int sceneCollected;
    private Vec3 sceneCamera = Vec3.ZERO;
    private double sceneDistanceMargin, sceneViewScale, sceneEntityDistance;
    private int sceneRenderDistance;
    private long sceneBuilds;
    private long sceneReuses;
    private boolean sceneShadows;
    private boolean sceneSkipVisibilityChecks;
    private ClientLevel level;
    private Vec3 camera = Vec3.ZERO;
    private double originX;
    private double originY;
    private double originZ;
    private float clock;
    private int ticks;
    private int program;
    private int entityProgram;
    private int preparedProgram;
    private GpuInstanceProcessor processor;
    private GpuShadowBatch shadows;
    private int generation;
    private int frame;
    private boolean active;
    private boolean failed;
    boolean capturing;
    public long uploadedBytes;
    public long modelBytes;
    public int instances;
    public int drawCalls;
    public int cacheMisses;
    public int shadowInstances;
    public int shadowDrawCalls;

    public void beginFrame(Camera camera, Matrix4f view, Matrix4f projection, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        this.camera = camera.getPosition();
        if (level != minecraft.level || Math.abs(this.camera.x - originX) > 1024
                || Math.abs(this.camera.y - originY) > 1024 || Math.abs(this.camera.z - originZ) > 1024) {
            clear();
            level = minecraft.level;
            originX = Math.floor(this.camera.x / 256) * 256;
            originY = Math.floor(this.camera.y / 256) * 256;
            originZ = Math.floor(this.camera.z / 256) * 256;
        }
        this.view.set(view);
        this.projection.set(projection);
        this.partialTick = partialTick;
        ticks = level == null ? 0 : (int) (level.getGameTime() % 24000);
        clock = ticks + partialTick;
        frame++;
        uploadedBytes = 0;
        modelBytes = 0;
        instances = 0;
        collected = 0;
        drawCalls = 0;
        cacheMisses = 0;
        shadowDrawCalls = 0;
        active = false;
        if (!failed && ItemModelOptimization.CONFIG.enabled.get() && GL.getCapabilities().OpenGL33) {
            if (program == 0) {
                int fragment = 0;
                int shadowFragment = 0;
                try {
                    fragment = compile(GL_FRAGMENT_SHADER, "instanced.fsh");
                    program = createProgram("instanced.vsh", fragment);
                    entityProgram = createProgram("entity_instanced.vsh", fragment);
                    shadowFragment = compile(GL_FRAGMENT_SHADER, "shadow.fsh");
                    shadows = new GpuShadowBatch(createProgram("shadow.vsh", shadowFragment));
                    if (GL.getCapabilities().OpenGL43) {
                        try {
                            preparedProgram = createProgram("entity_prepared.vsh", fragment);
                            processor = new GpuInstanceProcessor();
                        } catch (Exception exception) {
                            if (preparedProgram != 0) glDeleteProgram(preparedProgram);
                            preparedProgram = 0;
                            logger.warn("Compute animation unavailable; using vertex animation", exception);
                        }
                    }
                    logger.info("GPU item instancing ready: {} / {}", glGetString(GL_RENDERER), glGetString(GL_VERSION));
                } catch (Exception exception) {
                    if (program != 0) {
                        glDeleteProgram(program);
                        program = 0;
                    }
                    if (entityProgram != 0) {
                        glDeleteProgram(entityProgram);
                        entityProgram = 0;
                    }
                    failed = true;
                    logger.error("Instancing unavailable; retaining vanilla rendering", exception);
                } finally {
                    if (fragment != 0) {
                        glDeleteShader(fragment);
                    }
                    if (shadowFragment != 0) glDeleteShader(shadowFragment);
                }
            }
            active = program != 0 && entityProgram != 0;
        }
        sceneReused = active && processor != null && ItemModelOptimization.CONFIG.computeAnimation.get()
                && !minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes()
                && (minecraft.options.entityShadows().get() && ((ShadowSettings) minecraft.getEntityRenderDispatcher()).imo$shadows()) == sceneShadows
                && ItemModelOptimization.CONFIG.skipVisibilityChecks.get() && sceneSkipVisibilityChecks
                && sceneValid && sceneStationary && !sceneDirty && level.getSkyDarken() == sceneSkyDarken
                && level.getEntityCount() == sceneEntityCount && Entity.getViewScale() == sceneViewScale
                && minecraft.options.renderDistance().get() == sceneRenderDistance
                && minecraft.options.entityDistanceScaling().get() == sceneEntityDistance
                && camera.getPosition().distanceToSqr(sceneCamera) < sceneDistanceMargin * sceneDistanceMargin
                && !level.getChunkSource().getLightEngine().hasLightWork();
        if (sceneReused && sceneTick != level.getGameTime()) {
            for (ItemEntity item : sceneItems) {
                EntityCache cached = ((EntityCacheAccess) item).imo$cache();
                if (item.getAge() - ticks != cached.ageOffset || item.getItem() != cached.stack
                        || cached.version != ((ComponentVersion) cached.stack.getComponents()).imo$version()) {
                    sceneReused = false;
                    break;
                }
            }
            sceneTick = level.getGameTime();
        }
        if (sceneReused) {
            sceneReuses++;
            instances = sceneInstances;
            collected = sceneCollected;
        }
        if (shadows != null) {
            shadows.count = sceneReused ? shadows.retainedCount : 0;
            shadows.flushed = false;
            if (!sceneReused) shadows.receivers.clear();
        }
        if (!sceneReused) {
            lightCells.clear();
            lightCells.defaultReturnValue(-1);
        }
        shadowInstances = shadows == null ? 0 : shadows.count;
        for (List<GpuItemBatch> bucket : buckets.values()) {
            for (GpuItemBatch batch : bucket) {
                batch.count = sceneReused && batch.entityBatch ? batch.retainedCount : 0;
                if (batch.count > 0) batch.lastFrame = frame;
                batch.flushed = false;
                batch.processed = false;
            }
        }
    }

    public boolean submit(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose,
                          int light, int overlay, BakedModel model) {
        boolean submitted = false;
        if (active && !capturing && context == ItemDisplayContext.GROUND && !leftHand
                && overlay == OverlayTexture.NO_OVERLAY) {
            GpuItemBatch batch = find(stack, model, false);
            if (batch != null && !batch.flushed && batch.count < 65536) {
                transform.set(pose.last().pose());
                transform.m30(transform.m30() + (float) (camera.x - originX));
                transform.m31(transform.m31() + (float) (camera.y - originY));
                transform.m32(transform.m32() + (float) (camera.z - originZ));
                batch.add(transform, pose.last().normal(), 0, 0, false, light, false, 0, 0, 0);
                batch.lastFrame = frame;
                instances++;
                submitted = true;
            }
        }
        return submitted;
    }

    public boolean submitEntity(ItemEntity entity, float partialTick, int light) {
        boolean submitted = false;
        if (active && !capturing && entity.level() == level && !entity.isCurrentlyGlowing()) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) {
                return false;
            }
            int version = ((ComponentVersion) stack.getComponents()).imo$version();
            EntityCache cached = ((EntityCacheAccess) entity).imo$cache();
            var itemRenderer = Minecraft.getInstance().getItemRenderer();
            if (cached.stack != stack || cached.version != version || cached.generation != generation
                    || (cached.batch != null && cached.batch.closed)) {
                cached.stack = stack;
                cached.version = version;
                cached.generation = generation;
                cached.batch = null;
                BakedModel base = itemRenderer.getItemModelShaper().getItemModel(stack);
                cached.dynamicModel = base.getClass() == SimpleBakedModel.class && !base.getOverrides().getOverrides().isEmpty();
                if (base.getClass() == SimpleBakedModel.class && !cached.dynamicModel) {
                    cached.batch = find(stack, base, true);
                }
                cached.dynamicFoil = dynamicFoil.getOrDefault(stack.getItem().getClass(), true);
            }
            if (cached.batch == null && !cached.dynamicModel && cached.dynamicFoil && !stack.hasFoil()) {
                BakedModel model = itemRenderer.getModel(stack, level, null, entity.getId());
                cached.batch = find(stack, model, true);
            }
            if (cached.dynamicModel) {
                cached.batch = find(stack, itemRenderer.getModel(stack, level, null, entity.getId()), true);
            }
            GpuItemBatch batch = cached.batch;
            if (batch != null && cached.dynamicFoil && stack.hasFoil()) {
                batch = null;
            }
            if (batch != null && !batch.flushed) {
                ((ComponentVersion) stack.getComponents()).imo$track();
                int count = stack.getCount();
                int copies = count <= 1 ? 1 : count <= 16 ? 2 : count <= 32 ? 3 : count <= 48 ? 4 : 5;
                if (batch.count + copies <= 65536) {
                    IClientItemExtensions extensions = batch.extensions;
                    boolean spread = extensions == IClientItemExtensions.DEFAULT || extensions.shouldSpreadAsEntity(stack);
                    boolean bob = extensions == IClientItemExtensions.DEFAULT || extensions.shouldBobAsEntity(stack);
                    float ageOffset = entity.getAge() - (float) ticks;
                    cached.ageOffset = (int) ageOffset;
                    batch.addEntity((float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - originX),
                            (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - originY),
                            (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - originZ),
                            ageOffset, entity.bobOffs, light, bob, spread, copies);
                    batch.lastFrame = frame;
                    instances += copies;
                    submitted = true;
                }
            }
        }
        return submitted;
    }

    public void addShadow(ItemEntity entity, float partialTick, boolean enabled) {
        if (enabled && shadows != null && !shadows.flushed) {
            shadows.add(entity, partialTick, camera, originX, originY, originZ);
            shadowInstances = shadows.count;
        }
    }

    public Iterable<Entity> collect(Iterable<Entity> entities) {
        if (!active) return entities;
        var minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        if (dispatcher.shouldRenderHitBoxes()) return entities;
        if (sceneReused) return remainingView;
        remainingEntities.clear();
        sceneItems.clear();
        boolean stationary = true;
        boolean rendererChecked = false;
        boolean standardRenderer = false;
        boolean renderShadows = minecraft.options.entityShadows().get() && ((ShadowSettings) dispatcher).imo$shadows();
        boolean skipVisibility = ItemModelOptimization.CONFIG.skipVisibilityChecks.get();
        sceneDistanceMargin = Double.POSITIVE_INFINITY;
        for (Entity entity : entities) {
            boolean submitted = false;
            if (entity.getClass() == ItemEntity.class && entity.getType() == EntityType.ITEM
                    && entity != minecraft.getCameraEntity() && !entity.hasCustomName() && !entity.shouldShowName()
                    && !entity.displayFireAnimation() && !entity.isCurrentlyGlowing()) {
                ItemEntity item = (ItemEntity) entity;
                if (!rendererChecked) {
                    standardRenderer = dispatcher.getRenderer(item).getClass() == ItemEntityRenderer.class;
                    rendererChecked = true;
                }
                if (standardRenderer) {
                    double size = item.getBoundingBox().getSize();
                    if (Double.isNaN(size)) size = 1;
                    sceneDistanceMargin = Math.min(sceneDistanceMargin, Math.abs(size * 64 * Entity.getViewScale() - item.position().distanceTo(camera)));
                    if (skipVisibility ? !item.shouldRender(camera.x, camera.y, camera.z)
                            : !dispatcher.shouldRender(item, minecraft.levelRenderer.getFrustum(), camera.x, camera.y, camera.z)) continue;
                    Vec3 probe = item.getLightProbePosition(partialTick);
                    shadowPosition.set(Mth.floor(probe.x), Mth.floor(probe.y), Mth.floor(probe.z));
                    long key = shadowPosition.asLong();
                    int light = lightCells.get(key);
                    if (light == -1) {
                        light = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, shadowPosition), level.getBrightness(LightLayer.SKY, shadowPosition));
                        lightCells.put(key, light);
                    }
                    if (item.isOnFire()) light = (light & 0xFFFF0000) | 240;
                    submitted = submitEntity(item, partialTick, light);
                    if (submitted) {
                        collected++;
                        addShadow(item, partialTick, renderShadows);
                        EntityCache cached = ((EntityCacheAccess) item).imo$cache();
                        sceneItems.add(item);
                        stationary &= item.xOld == item.getX() && item.yOld == item.getY() && item.zOld == item.getZ()
                                && !cached.dynamicModel && !cached.dynamicFoil
                                && cached.batch.extensions == IClientItemExtensions.DEFAULT;
                    }
                }
            }
            if (!submitted) {
                remainingEntities.add(entity);
                if (entity instanceof ItemEntity) stationary = false;
            }
        }
        for (List<GpuItemBatch> bucket : buckets.values()) {
            for (GpuItemBatch batch : bucket) if (batch.entityBatch) batch.retainedCount = batch.count;
        }
        if (shadows != null) shadows.retainedCount = shadows.count;
        sceneValid = true;
        sceneBuilds++;
        sceneStationary = stationary;
        sceneDirty = false;
        sceneTick = level.getGameTime();
        sceneSkyDarken = level.getSkyDarken();
        sceneEntityCount = level.getEntityCount();
        sceneInstances = instances;
        sceneCollected = collected;
        sceneCamera = camera;
        sceneViewScale = Entity.getViewScale();
        sceneRenderDistance = minecraft.options.renderDistance().get();
        sceneEntityDistance = minecraft.options.entityDistanceScaling().get();
        sceneShadows = renderShadows;
        sceneSkipVisibilityChecks = skipVisibility;
        return remainingView;
    }

    private GpuItemBatch find(ItemStack stack, BakedModel model, boolean entityBatch) {
        if (stack.isEmpty()) {
            return null;
        }
        int version = ((ComponentVersion) stack.getComponents()).imo$version();
        StackCache cached = stacks.get(stack);
        if (cached != null && cached.version == version && cached.model == model && cached.entityBatch == entityBatch
                && (cached.batch == null || !cached.batch.closed)) {
            cached.lastFrame = frame;
            return cached.batch != null && cached.dynamicFoil && stack.hasFoil() ? null : cached.batch;
        }
        GpuItemBatch result = null;
        boolean checkFoil = false;
        boolean cacheable = true;
        if (model.getClass() == SimpleBakedModel.class && !rejected.contains(model)
                && !stack.is(Items.TRIDENT) && !stack.is(Items.SPYGLASS)) {
            checkFoil = !stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
                    && dynamicFoil.computeIfAbsent(stack.getItem().getClass(), type -> {
                        try {
                            return type.getMethod("isFoil", ItemStack.class).getDeclaringClass() != Item.class;
                        } catch (ReflectiveOperationException exception) {
                            return true;
                        }
                    });
            if (stack.hasFoil()) {
                cacheable = !checkFoil;
            } else {
                int hash = ItemStack.hashItemAndComponents(stack);
                List<GpuItemBatch> bucket = buckets.get(hash);
                if (bucket != null) {
                    for (GpuItemBatch candidate : bucket) {
                        if (candidate.model == model && candidate.entityBatch == entityBatch && ItemStack.isSameItemSameComponents(candidate.stack, stack)) {
                            result = candidate;
                            break;
                        }
                    }
                }
                if (result == null) {
                    boolean supported = true;
                    for (BakedModel pass : model.getRenderPasses(stack, true)) {
                        for (RenderType type : pass.getRenderTypes(stack, true)) {
                            supported &= type == RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS) || type == RenderType.itemEntityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);
                        }
                    }
                    if (supported) {
                        try {
                            result = new GpuItemBatch(stack, model, this, entityBatch);
                            if (bucket == null) {
                                bucket = new ArrayList<>();
                                buckets.put(hash, bucket);
                            }
                            bucket.add(result);
                            cacheMisses++;
                            modelBytes += result.meshBytes;
                        } catch (Exception exception) {
                            logger.warn("Keeping vanilla renderer for {}: {}", stack.getItem(), exception.toString());
                        }
                    } else {
                        rejected.add(model);
                    }
                }
            }
        }
        if (cacheable) {
            if (cached == null) {
                cached = new StackCache();
                stacks.put(stack, cached);
            }
            cached.version = version;
            cached.model = model;
            cached.batch = result;
            cached.entityBatch = entityBatch;
            cached.dynamicFoil = checkFoil;
            cached.lastFrame = frame;
        }
        return result;
    }

    public void flush() {
        if (active) {
            int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int buffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
            try (MemoryStack memory = MemoryStack.stackPush()) {
                for (List<GpuItemBatch> bucket : buckets.values()) {
                    for (GpuItemBatch batch : bucket) {
                        if (batch.count > 0 && !batch.flushed) {
                            batch.processed = batch.entityBatch && processor != null && ItemModelOptimization.CONFIG.computeAnimation.get();
                            int drawProgram = batch.entityBatch ? batch.processed ? preparedProgram : entityProgram : program;
                            uploadedBytes += batch.upload();
                            if (batch.processed) processor.prepare(batch, clock);
                            for (GpuItemBatch.Mesh mesh : batch.meshes) {
                                mesh.type.setupRenderState();
                                try {
                                    ShaderInstance vanilla = RenderSystem.getShader();
                                    if (vanilla != null) {
                                        RenderSystem.setupShaderLights(vanilla);
                                        for (int sampler = 0; sampler < 3; sampler++) {
                                            vanilla.setSampler("Sampler" + sampler, RenderSystem.getShaderTexture(sampler));
                                        }
                                        vanilla.apply();
                                    }
                                    glUseProgram(drawProgram);
                                    glUniformMatrix4fv(glGetUniformLocation(drawProgram, "View"), false, view.get(memory.mallocFloat(16)));
                                    glUniformMatrix4fv(glGetUniformLocation(drawProgram, "Projection"), false, projection.get(memory.mallocFloat(16)));
                                    glUniform3f(glGetUniformLocation(drawProgram, "Camera"), (float) (camera.x - originX),
                                            (float) (camera.y - originY), (float) (camera.z - originZ));
                                    glUniform1f(glGetUniformLocation(drawProgram, "Clock"), clock);
                                    glUniform1f(glGetUniformLocation(drawProgram, "GroundScaleY"), batch.groundScaleY);
                                    for (int sampler = 0; sampler < 3; sampler++) {
                                        glUniform1i(glGetUniformLocation(drawProgram, "Sampler" + sampler), sampler);
                                    }
                                    float[] fog = RenderSystem.getShaderFogColor();
                                    glUniform4f(glGetUniformLocation(drawProgram, "FogColor"), fog[0], fog[1], fog[2], fog[3]);
                                    float[] color = RenderSystem.getShaderColor();
                                    glUniform4f(glGetUniformLocation(drawProgram, "ColorModulator"), color[0], color[1], color[2], color[3]);
                                    glUniform1f(glGetUniformLocation(drawProgram, "FogStart"), RenderSystem.getShaderFogStart());
                                    glUniform1f(glGetUniformLocation(drawProgram, "FogEnd"), RenderSystem.getShaderFogEnd());
                                    glUniform1i(glGetUniformLocation(drawProgram, "FogShape"), RenderSystem.getShaderFogShape().getIndex());
                                    if (vanilla != null && vanilla.LIGHT0_DIRECTION != null && vanilla.LIGHT1_DIRECTION != null) {
                                        glUniform3fv(glGetUniformLocation(drawProgram, "Light0"), vanilla.LIGHT0_DIRECTION.getFloatBuffer());
                                        glUniform3fv(glGetUniformLocation(drawProgram, "Light1"), vanilla.LIGHT1_DIRECTION.getFloatBuffer());
                                    }
                                    mesh.bind();
                                    glDrawElementsInstanced(GL_TRIANGLES, mesh.indices, GL_UNSIGNED_INT, 0, batch.count);
                                    drawCalls++;
                                    if (vanilla != null) {
                                        vanilla.clear();
                                    }
                                } finally {
                                    mesh.type.clearRenderState();
                                }
                            }
                            batch.flushed = true;
                        }
                    }
                }
                if (shadows != null && shadows.count > 0 && !shadows.flushed) {
                    uploadedBytes += shadows.draw(view, projection, camera, originX, originY, originZ);
                    shadowDrawCalls++;
                }
            } finally {
                glUseProgram(previousProgram);
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
            }
        }
    }

    public void endFrame() {
        active = false;
        long allocated = 0;
        int count = 0;
        for (List<GpuItemBatch> bucket : buckets.values()) {
            for (GpuItemBatch batch : bucket) {
                allocated += batch.meshBytes + batch.capacity * (long) batch.instanceStride + batch.preparedCapacity * 40L;
                count++;
            }
        }
        Iterator<List<GpuItemBatch>> bucketsIterator = buckets.values().iterator();
        while (bucketsIterator.hasNext()) {
            List<GpuItemBatch> bucket = bucketsIterator.next();
            Iterator<GpuItemBatch> iterator = bucket.iterator();
            while (iterator.hasNext()) {
                GpuItemBatch batch = iterator.next();
                if (frame - batch.lastFrame > 300 || (batch.lastFrame != frame && (allocated > 67108864 || count > 256))) {
                    allocated -= batch.meshBytes + batch.capacity * (long) batch.instanceStride + batch.preparedCapacity * 40L;
                    count--;
                    batch.close();
                    iterator.remove();
                }
            }
            if (bucket.isEmpty()) {
                bucketsIterator.remove();
            }
        }
        boolean trimStacks = stacks.size() > 16384;
        if (trimStacks || (frame & 63) == 0) {
            var iterator = stacks.reference2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                StackCache cached = iterator.next().getValue();
                if (frame - cached.lastFrame > 300 || (cached.batch != null && cached.batch.closed)
                        || (trimStacks && cached.lastFrame != frame)) {
                    iterator.remove();
                }
            }
        }
    }

    public String statistics() {
        return String.format(Locale.ROOT, "%d instances (%d collected), %d instanced draws, %d shadow instances/%d shadow draws, %d new meshes, %d instance bytes/frame, %d mesh bytes/frame, %d scene builds/%d reuses",
                instances, collected, drawCalls, shadowInstances, shadowDrawCalls, cacheMisses, uploadedBytes, modelBytes, sceneBuilds, sceneReuses);
    }

    public void invalidate() {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            clear();
        } else {
            RenderSystem.recordRenderCall(this::clear);
        }
    }

    private void clear() {
        active = false;
        generation++;
        for (List<GpuItemBatch> bucket : buckets.values()) {
            for (GpuItemBatch batch : bucket) {
                batch.close();
            }
        }
        buckets.clear();
        stacks.clear();
        dynamicFoil.clear();
        rejected.clear();
        opaqueSprites.clear();
        lightCells.clear();
        remainingEntities.clear();
        sceneItems.clear();
        collecting = false;
        sceneValid = false;
        sceneDirty = true;
        sceneBuilds = 0;
        sceneReuses = 0;
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
        if (entityProgram != 0) {
            glDeleteProgram(entityProgram);
            entityProgram = 0;
        }
        if (processor != null) {
            processor.close();
            processor = null;
        }
        if (shadows != null) {
            shadows.close();
            shadows = null;
        }
        if (preparedProgram != 0) {
            glDeleteProgram(preparedProgram);
            preparedProgram = 0;
        }
        failed = false;
    }

    private int compile(int type, String name) throws IOException {
        String source;
        try (InputStream stream = getClass().getResourceAsStream("/assets/item_model_optimization/shaders/" + name)) {
            if (stream == null) {
                throw new IOException("Missing shader " + name);
            }
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException(error);
        }
        return shader;
    }

    private int createProgram(String name, int fragment) throws IOException {
        int vertex = compile(GL_VERTEX_SHADER, name);
        int result = glCreateProgram();
        try {
            glAttachShader(result, vertex);
            glAttachShader(result, fragment);
            glLinkProgram(result);
            if (glGetProgrami(result, GL_LINK_STATUS) == GL_FALSE) {
                throw new IOException(glGetProgramInfoLog(result));
            }
        } catch (IOException exception) {
            glDeleteProgram(result);
            throw exception;
        } finally {
            glDeleteShader(vertex);
        }
        return result;
    }

    public interface ComponentVersion {
        int imo$version();
        void imo$track();
        boolean imo$tracked();
    }

    public interface ShadowSettings {
        boolean imo$shadows();
    }

    public interface EntityCacheAccess {
        EntityCache imo$cache();
    }

    public static final class EntityCache {
        private ItemStack stack;
        private GpuItemBatch batch;
        private int version;
        private int generation = -1;
        private int ageOffset;
        private boolean dynamicModel;
        private boolean dynamicFoil;
    }

    private static final class StackCache {
        private BakedModel model;
        private GpuItemBatch batch;
        private int version;
        private int lastFrame;
        private boolean dynamicFoil;
        private boolean entityBatch;
    }
}
