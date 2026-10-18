import assert from 'node:assert/strict'
import { createElasticMeshPhysics } from './elastic-mesh-physics'

// 在官网目录运行：bun components/3d/elastic-mesh-physics.test.ts
// 检查真实受力与释放过程，不依赖浏览器、GPU 或测试框架。
const mesh = createElasticMeshPhysics(24)
mesh.setAspect(2.6)
for (let step = 0; step < 240; step++) mesh.step({ x: 1, y: 0, active: true })
assert.ok(mesh.commit().maxOffset > 0.01, '受力时应出现可见形变')
for (let step = 0; step < 1200; step++) mesh.step({ x: 1, y: 0, active: false })
const released = mesh.commit()
assert.ok(released.maxOffset < 0.00001, '松开后应归位')
assert.ok(released.maxVelocity < 0.00001, '回弹后应静止')
for (const value of [...mesh.offsets, ...mesh.normals])
  assert.ok(Number.isFinite(value), '位移与法线不能出现非有限数值')
for (let index = 0; index < mesh.normals.length; index += 3)
  assert.ok(
    Math.abs(Math.hypot(...mesh.normals.slice(index, index + 3)) - 1) < 0.00001,
    '法线应保持单位长度',
  )
assert.ok(
  Math.max(...mesh.indices) < mesh.grid.length / 2,
  '三角形索引不能越界',
)
mesh.reset()
assert.equal(mesh.commit().maxOffset, 0, '减少动画时应完全归位')
console.log('Elastic Mesh 物理自检通过')
