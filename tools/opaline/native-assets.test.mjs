import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile, readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const source = new URL('../../ui-system/Opaline-3D-Library/assets/', import.meta.url);
const native = new URL('../../app/src/main/assets/opaline-native/', import.meta.url);
const names = await readdir(native);
const hash = b => createHash('sha256').update(b).digest('hex');
for (const name of names.filter(n => n.endsWith('.glb'))) {
  test(`${name}: original library geometry and in-bounds finite accessors`, async () => {
    const b = await readFile(new URL(name, native));
    assert.equal(hash(b), hash(await readFile(new URL(`models/${name}`, source))));
    assert.equal(b.readUInt32LE(0), 0x46546c67);
    assert.equal(b.readUInt32LE(4), 2);
    assert.equal(b.readUInt32LE(8), b.length);
    const jsonLength = b.readUInt32LE(12);
    const gltf = JSON.parse(b.toString('utf8', 20, 20 + jsonLength));
    const binary = b.subarray(28 + jsonLength);
    // J24 (the catalogue's scene light rig) carries punctual lights only: no accessors, no mesh.
    for (const a of gltf.accessors ?? []) {
      const v = gltf.bufferViews[a.bufferView];
      const components = {SCALAR:1,VEC2:2,VEC3:3,VEC4:4}[a.type];
      const bytes = {5121:1,5123:2,5125:4,5126:4}[a.componentType];
      const stride = v.byteStride ?? components * bytes;
      const start = (v.byteOffset ?? 0) + (a.byteOffset ?? 0);
      assert.ok(start + (a.count - 1) * stride + components * bytes <= binary.length);
      if (a.componentType === 5126) for(let i=0;i<a.count;i++) for(let j=0;j<components;j++) {
        assert.ok(Number.isFinite(binary.readFloatLE(start+i*stride+j*bytes)));
      }
    }
    assert.ok(gltf.nodes.some(n => n.mesh !== undefined || n.extensions?.KHR_lights_punctual !== undefined));
  });
}
for (const name of names.filter(n => n.endsWith('.png'))) {
  test(`${name}: original distant artwork, no generated replacement`, async () => {
    assert.equal(hash(await readFile(new URL(name, native))), hash(await readFile(new URL(`artwork/${name}`, source))));
  });
}
