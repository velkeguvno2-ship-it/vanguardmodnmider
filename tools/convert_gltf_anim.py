"""glTF (Blockbench) animations -> .anim for the Desert Vanguard PMX skeleton.

Usage: python convert_gltf_anim.py <vanguard.gltf> <model.pmx> <out.anim>

The Blockbench model (animations idle / walk / stab) and the PMX model have different skeletons, so every
animation is sampled at FPS frames per second and converted into the PMX bone hierarchy:
  * gltf node -> PMX bone mapping is in MAP below,
  * the glTF model faces -Z (right arm on +X), the mod faces +Z, so everything is rotated 180 deg around Y,
  * shield / glaive have a different "rest" orientation in the PMX than in the Blockbench model; the constant
    correction Q (found by fitting the two meshes onto each other) is in ITEM_FIX.
Per frame and bone the file stores the LOCAL rotation (quaternion) and the translation offset (blocks).
"""
import base64, json, math, struct, sys
import numpy as np
from scipy.spatial.transform import Rotation as Rot
import pmx

FPS = 24
SCALE = 0.125
Y180 = np.diag([-1.0, 1.0, -1.0])
MIR = np.diag([1.0, 1.0, -1.0])

# PMX bone -> (gltf node name, kind). Kind: "node" = geometry is in the node's own frame,
# "item" = geometry sits in world frame at rest (divide by the rest orientation, then apply Q)
MAP = {
    "J_L_Hip":    ("waist",     "node", None),
    "J_L_Spine":  ("body",      "node", None),
    "J_Neck":     ("head",      "node", None),
    "J_R_Arm":    ("rightArm",  "node", None),
    "J_L_Arm":    ("leftArm",   "node", None),
    "Shield":     ("rightitem", "item", np.array([[1, 0, 0], [0, 0, 1], [0, -1, 0]], float)),
    "J_L_Weapon": ("leftitem",  "item", np.eye(3)),
    "J_R_Leg":    ("rightLeg",  "node", None),
    "J_L_Leg":    ("leftLeg",   "node", None),
}
# body lean is done around the hip in Blockbench, the PMX spine bone sits a bit higher -> correct the offset
LEAN_PIVOT_BONE = ("J_L_Spine", "J_L_Hip")


def load_gltf(path):
    g = json.load(open(path))
    uri = g["buffers"][0]["uri"]
    data = base64.b64decode(uri.split(",", 1)[1]) if uri.startswith("data:") else open(path.rsplit("/", 1)[0] + "/" + uri, "rb").read()
    return g, data


def accessor(g, data, i):
    a = g["accessors"][i]; bv = g["bufferViews"][a["bufferView"]]
    n = {"SCALAR": 1, "VEC3": 3, "VEC4": 4}[a["type"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    return np.frombuffer(data, dtype=np.float32, count=a["count"] * n, offset=off).reshape(a["count"], n)


def slerp(a, b, t):
    a = np.array(a, float); b = np.array(b, float)
    d = a @ b
    if d < 0: b = -b; d = -d
    if d > 0.9995:
        r = a + (b - a) * t
    else:
        th = math.acos(min(d, 1.0)); r = (math.sin((1 - t) * th) * a + math.sin(t * th) * b) / math.sin(th)
    return r / np.linalg.norm(r)


def sample(track, t):
    ts, vs = track
    if len(ts) == 1 or t <= ts[0]: return vs[0].astype(float)
    if t >= ts[-1]: return vs[-1].astype(float)
    i = int(np.searchsorted(ts, t)) - 1
    f = (t - ts[i]) / (ts[i + 1] - ts[i])
    return slerp(vs[i], vs[i + 1], f) if vs.shape[1] == 4 else vs[i] * (1 - f) + vs[i + 1] * f


class Gltf:
    def __init__(self, path):
        self.g, self.data = load_gltf(path)
        self.nodes = self.g["nodes"]
        self.parent = {}
        for i, n in enumerate(self.nodes):
            for c in n.get("children", []): self.parent[c] = i
        self.byname = {}
        for i, n in enumerate(self.nodes):
            if "mesh" not in n and n.get("name"): self.byname[n["name"]] = i
        self.anims = {}
        for a in self.g["animations"]:
            name = a["name"].split(".")[-1]
            tr = {}
            for ch in a["channels"]:
                s = a["samplers"][ch["sampler"]]
                tr[(ch["target"]["node"], ch["target"]["path"])] = (accessor(self.g, self.data, s["input"])[:, 0],
                                                                   accessor(self.g, self.data, s["output"]))
            length = max(float(t[0][-1]) for t in tr.values())
            self.anims[name] = (length, tr)

    def local(self, i, tr, t):
        n = self.nodes[i]
        p = np.array(n.get("translation", [0, 0, 0]), float)
        q = np.array(n.get("rotation", [0, 0, 0, 1]), float)
        if tr:
            if (i, "translation") in tr: p = sample(tr[(i, "translation")], t)
            if (i, "rotation") in tr: q = sample(tr[(i, "rotation")], t)
        return p, q

    def world_rot(self, i, tr, t):
        _, q = self.local(i, tr, t)
        R = Rot.from_quat(q).as_matrix()
        return self.world_rot(self.parent[i], tr, t) @ R if i in self.parent else R


def conj(R):
    return Y180 @ R @ Y180


def bake(gl, pmx_model):
    B = pmx_model["B"]
    names = [b[0] for b in B]
    parent = [b[2] for b in B]
    piv = [MIR @ np.array(b[1]) * SCALE for b in B]
    idx = {n: i for i, n in enumerate(names)}
    out = {}
    for an, (length, tr) in gl.anims.items():
        loop = an != "stab"
        frames = int(round(length * FPS)) + (0 if loop else 1)
        data = {b: [] for b in MAP}
        for f in range(frames):
            t = f / FPS
            Rw = [None] * len(B)  # PMX world rotation
            for i, name in enumerate(names):
                if name in MAP:
                    node, kind, Q = MAP[name]
                    n = gl.byname[node]
                    Rg = gl.world_rot(n, tr, t)
                    if kind == "item":
                        Rg = Rg @ gl.world_rot(n, None, 0).T
                        target = conj(Rg) @ Q
                    else:
                        target = conj(Rg)
                else:
                    target = Rw[parent[i]] if parent[i] >= 0 else np.eye(3)
                Rw[i] = target
            for name, (node, kind, Q) in MAP.items():
                i = idx[name]
                n = gl.byname[node]
                loc = Rw[parent[i]].T @ Rw[i] if parent[i] >= 0 else Rw[i]
                p, _ = gl.local(n, tr, t)
                prest, _ = gl.local(n, None, 0)
                d = Y180 @ (p - prest)
                if name == LEAN_PIVOT_BONE[0]:
                    h = idx[LEAN_PIVOT_BONE[1]]
                    d = d + (Rw[i] - np.eye(3)) @ (piv[i] - piv[h])
                q = Rot.from_matrix(loc).as_quat()
                data[name].append((*q, *d))
        out[an] = (length, loop, frames, data)
    return out


def write(path, baked):
    out = bytearray(struct.pack(">ii", FPS, len(baked)))
    for an, (length, loop, frames, data) in baked.items():
        s = an.encode()
        out += struct.pack(">H", len(s)) + s + struct.pack(">f?ii", length, loop, frames, len(data))
        for bone, fr in data.items():
            s = bone.encode()
            out += struct.pack(">H", len(s)) + s
            for v in fr: out += struct.pack(">7f", *v)
    open(path, "wb").write(out)


if __name__ == "__main__":
    gl = Gltf(sys.argv[1])
    baked = bake(gl, pmx.load(sys.argv[2]))
    write(sys.argv[3], baked)
    print("wrote", sys.argv[3], {k: (round(v[0], 3), v[2]) for k, v in baked.items()})
