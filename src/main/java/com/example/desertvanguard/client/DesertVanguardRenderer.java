package com.example.desertvanguard.client;

import com.example.desertvanguard.DesertVanguardMod;
import com.example.desertvanguard.entity.DesertVanguardEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Renders the skeletal mesh (models/entity/desert_vanguard.mesh) and plays the animations from
 * models/entity/desert_vanguard.anim (idle / walk / stab, baked from the Blockbench model by tools/convert_gltf_anim.py).
 * Idle loops all the time, walk is blended in by how fast the mob moves.
 */
public class DesertVanguardRenderer extends EntityRenderer<DesertVanguardEntity> {

    private static final ResourceLocation MESH =
            new ResourceLocation(DesertVanguardMod.MODID, "models/entity/desert_vanguard.mesh");
    private static final ResourceLocation ANIM =
            new ResourceLocation(DesertVanguardMod.MODID, "models/entity/desert_vanguard.anim");

    // Same order as the texture list in the PMX: skeleton, armor, glaive, shield
    private static final ResourceLocation[] TEXTURES = {
            tex("skeleton"), tex("desert_armor"), tex("desert_weapon"), tex("desert_shield")
    };

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(DesertVanguardMod.MODID, "textures/entity/desert_vanguard/" + name + ".png");
    }

    /** How much walkDist (blocks * 0.6) one full walk cycle (two steps) takes. */
    private static final float WALK_CYCLE_DIST = 0.9F;

    private record Bone(String name, int parent, Vector3f pivot) {}
    private record Part(int bone, int texture, int triangles, float[] data) {}
    /** One baked animation: for every animated bone (bones[k]) frames * 7 floats = local quaternion xyzw + offset xyz. */
    private record Anim(float length, boolean loop, int frames, int[] bones, float[][] data) {}

    private final Bone[] bones;
    private final Part[] parts;
    private final Matrix4f[] world;
    private final Anim idle;
    private final Anim walk;
    private final float fps;

    // per-bone pose, rebuilt every frame
    private final Quaternionf[] poseQ;
    private final Vector3f[] poseP;
    private final Quaternionf tmpQ = new Quaternionf();
    private final Quaternionf tmpQ2 = new Quaternionf();

    public DesertVanguardRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5F;
        Bone[][] boneOut = new Bone[1][];
        this.parts = loadMesh(context.getResourceManager(), boneOut);
        this.bones = boneOut[0];
        this.world = new Matrix4f[bones.length];
        this.poseQ = new Quaternionf[bones.length];
        this.poseP = new Vector3f[bones.length];
        for (int i = 0; i < world.length; i++) {
            world[i] = new Matrix4f();
            poseQ[i] = new Quaternionf();
            poseP[i] = new Vector3f();
        }
        float[] fpsOut = new float[1];
        java.util.Map<String, Anim> anims = loadAnims(context.getResourceManager(), bones, fpsOut);
        this.fps = fpsOut[0];
        this.idle = anims.get("idle");
        this.walk = anims.get("walk");
    }

    private static java.util.Map<String, Anim> loadAnims(ResourceManager manager, Bone[] bones, float[] fpsOut) {
        java.util.Map<String, Anim> result = new java.util.HashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(manager.getResourceOrThrow(ANIM).open()))) {
            fpsOut[0] = in.readInt();
            int count = in.readInt();
            for (int a = 0; a < count; a++) {
                String name = in.readUTF();
                float length = in.readFloat();
                boolean loop = in.readBoolean();
                int frames = in.readInt();
                int tracks = in.readInt();
                int[] boneIdx = new int[tracks];
                float[][] data = new float[tracks][frames * 7];
                for (int k = 0; k < tracks; k++) {
                    String boneName = in.readUTF();
                    boneIdx[k] = -1;
                    for (int i = 0; i < bones.length; i++) if (bones[i].name().equals(boneName)) boneIdx[k] = i;
                    for (int i = 0; i < data[k].length; i++) data[k][i] = in.readFloat();
                }
                result.put(name, new Anim(length, loop, frames, boneIdx, data));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Could not load " + ANIM, e);
        }
    }

    /** Interpolates animation {@code a} at {@code time} seconds for track k into q / p. */
    private void sampleTrack(Anim a, int k, float time, Quaternionf q, Vector3f p) {
        float f = time * fps;
        int i0, i1;
        if (a.loop()) {
            f = ((f % a.frames()) + a.frames()) % a.frames();
            i0 = (int) f;
            i1 = (i0 + 1) % a.frames();
        } else {
            f = Mth.clamp(f, 0.0F, a.frames() - 1);
            i0 = (int) f;
            i1 = Math.min(i0 + 1, a.frames() - 1);
        }
        float t = f - i0;
        float[] d = a.data()[k];
        int o0 = i0 * 7, o1 = i1 * 7;
        q.set(d[o0], d[o0 + 1], d[o0 + 2], d[o0 + 3])
                .slerp(tmpQ2.set(d[o1], d[o1 + 1], d[o1 + 2], d[o1 + 3]), t);
        p.set(Mth.lerp(t, d[o0 + 4], d[o1 + 4]), Mth.lerp(t, d[o0 + 5], d[o1 + 5]), Mth.lerp(t, d[o0 + 6], d[o1 + 6]));
    }

    private static Part[] loadMesh(ResourceManager manager, Bone[][] boneOut) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(manager.getResourceOrThrow(MESH).open()))) {
            Bone[] bones = new Bone[in.readInt()];
            for (int i = 0; i < bones.length; i++) {
                String name = in.readUTF();
                int parent = in.readInt();
                Vector3f pivot = new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
                in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat(); // old baked static pose quaternion - unused, poses come from the .anim file
                bones[i] = new Bone(name, parent, pivot);
            }
            boneOut[0] = bones;
            Part[] result = new Part[in.readInt()];
            for (int p = 0; p < result.length; p++) {
                int bone = in.readInt();
                int texture = in.readInt();
                int tris = in.readInt();
                float[] data = new float[tris * 3 * 8];
                for (int i = 0; i < data.length; i++) data[i] = in.readFloat();
                result[p] = new Part(bone, texture, tris, data);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Could not load " + MESH, e);
        }
    }

    @Override
    public void render(DesertVanguardEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));

        if (entity.deathTime > 0) {
            float f = ((float) entity.deathTime + partialTick - 1.0F) / 20.0F * 1.6F;
            f = Math.min(Mth.sqrt(f), 1.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(f * 90.0F));
        }

        // --- pose: idle always loops, walk is blended in by movement speed ---
        float amp = entity.deathTime > 0 ? 0.0F : Mth.lerp(partialTick, entity.walkAmpO, entity.walkAmp);
        float idleTime = (entity.tickCount + partialTick) / 20.0F;
        float walkTime = Mth.lerp(partialTick, entity.walkDistO, entity.walkDist) / WALK_CYCLE_DIST * walk.length();
        for (int i = 0; i < bones.length; i++) {
            poseQ[i].identity();
            poseP[i].zero();
        }
        Vector3f wp = new Vector3f();
        for (int k = 0; k < idle.bones().length; k++) {
            int bi = idle.bones()[k];
            if (bi < 0) continue;
            sampleTrack(idle, k, idleTime, poseQ[bi], poseP[bi]);
            if (amp > 0.001F) {
                for (int w = 0; w < walk.bones().length; w++) {
                    if (walk.bones()[w] != bi) continue;
                    sampleTrack(walk, w, walkTime, tmpQ, wp);
                    poseQ[bi].slerp(tmpQ, amp);
                    poseP[bi].lerp(wp, amp);
                    break;
                }
            }
        }

        for (int i = 0; i < bones.length; i++) {
            Bone b = bones[i];
            Matrix4f m = world[i];
            if (b.parent() < 0) {
                m.translation(b.pivot()).translate(poseP[i]);
            } else {
                Vector3f pp = bones[b.parent()].pivot();
                m.set(world[b.parent()]).translate(b.pivot().x - pp.x + poseP[i].x,
                        b.pivot().y - pp.y + poseP[i].y, b.pivot().z - pp.z + poseP[i].z);
            }
            m.rotate(poseQ[i]);
        }

        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        PoseStack.Pose pose = poseStack.last();

        for (Part part : parts) {
            Matrix4f pm = new Matrix4f(pose.pose()).mul(world[part.bone()]);
            Matrix3f nm = new Matrix3f(pose.normal()).mul(new Matrix3f(world[part.bone()]));
            VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURES[part.texture()]));
            float[] d = part.data();
            for (int t = 0; t < part.triangles(); t++) {
                int base = t * 24;
                // entity render types use QUADS, so the last vertex of the triangle is emitted twice
                emit(vc, pm, nm, d, base, overlay, packedLight);
                emit(vc, pm, nm, d, base + 8, overlay, packedLight);
                emit(vc, pm, nm, d, base + 16, overlay, packedLight);
                emit(vc, pm, nm, d, base + 16, overlay, packedLight);
            }
        }

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static void emit(VertexConsumer vc, Matrix4f m, Matrix3f n, float[] d, int o, int overlay, int light) {
        vc.vertex(m, d[o], d[o + 1], d[o + 2])
                .color(255, 255, 255, 255)
                .uv(d[o + 6], d[o + 7])
                .overlayCoords(overlay)
                .uv2(light)
                .normal(n, d[o + 3], d[o + 4], d[o + 5])
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(DesertVanguardEntity entity) {
        return TEXTURES[0];
    }
}
