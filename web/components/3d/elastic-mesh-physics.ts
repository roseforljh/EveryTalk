// 改编自 React Bits ElasticMesh 的弹簧耦合与法线重建，独立于渲染器。
// Copyright (c) 2026 David Haz. 许可与来源见 BLACK_WHITE_REDESIGN.md。

export interface MeshPointer {
  x: number
  y: number
  active: boolean
}

/** 创建固定尺寸网格；step 推进一次 1/120 秒模拟，commit 写入 GPU 用数组并返回剩余运动量。 */
export function createElasticMeshPhysics(resolution = 24) {
  const settings = {
    stiffness: 0.05,
    damping: 0.2,
    grabRadius: 0.6,
    pull: 0.4,
    wobble: 5,
  }
  const N = Math.max(6, Math.min(40, Math.round(resolution)))
  const nodeCount = N * N

  // 规则网格与 UV 不随交互改变；GPU 每帧只更新位移和法线。
  const aGrid = new Float32Array(nodeCount * 2)
  const uv = new Float32Array(nodeCount * 2)
  const aOffset = new Float32Array(nodeCount * 3)
  const aNormal = new Float32Array(nodeCount * 3)

  for (let j = 0; j < N; j++) {
    for (let i = 0; i < N; i++) {
      const idx = j * N + i
      const u = i / (N - 1)
      const v = j / (N - 1)
      aGrid[idx * 2] = u
      aGrid[idx * 2 + 1] = v
      uv[idx * 2] = u
      uv[idx * 2 + 1] = v
      aNormal[idx * 3 + 2] = 1
    }
  }

  const quads = (N - 1) * (N - 1)
  const index = new Uint16Array(quads * 6)
  let t = 0
  for (let j = 0; j < N - 1; j++) {
    for (let i = 0; i < N - 1; i++) {
      const a = j * N + i
      const b = a + 1
      const c = a + N
      const d = c + 1
      index[t++] = a
      index[t++] = c
      index[t++] = b
      index[t++] = b
      index[t++] = c
      index[t++] = d
    }
  }

  const baseX = new Float32Array(nodeCount)
  const baseY = new Float32Array(nodeCount)
  const pos = new Float32Array(nodeCount * 3)
  const vel = new Float32Array(nodeCount * 3)
  const accel = new Float32Array(nodeCount * 3)

  let aspect = 1
  /** 视口比例改变时重算平面基准点，避免宽屏上的抓取位置偏移。 */
  function refreshBase(): void {
    for (let idx = 0; idx < nodeCount; idx++) {
      baseX[idx] = (aGrid[idx * 2] * 2 - 1) * aspect
      baseY[idx] = 1 - aGrid[idx * 2 + 1] * 2
    }
  }

  let maxOffset = 0
  let maxVel = 0
  /** 原始弹簧耦合：每个节点向原位回弹，同时跟随上下左右邻居，形成连续曲面。 */
  function step(pointer: MeshPointer): void {
    const p = settings
    const s = p.stiffness
    const retain = 1 - p.damping
    const coupling = 0.06 + p.wobble * 0.032
    const active = pointer.active
    const r = Math.max(0.08, p.grabRadius) * 1.4
    const invR = 1 / r
    const force = p.pull * 0.009

    for (let j = 0; j < N; j++) {
      for (let i = 0; i < N; i++) {
        const idx = j * N + i
        const o3 = idx * 3
        const ox = pos[o3]
        const oy = pos[o3 + 1]
        const oz = pos[o3 + 2]

        let ax = -s * ox
        let ay = -s * oy
        let az = -s * oz

        let sumx = 0
        let sumy = 0
        let sumz = 0
        let cnt = 0
        if (i > 0) {
          const n = (idx - 1) * 3
          sumx += pos[n]
          sumy += pos[n + 1]
          sumz += pos[n + 2]
          cnt++
        }
        if (i < N - 1) {
          const n = (idx + 1) * 3
          sumx += pos[n]
          sumy += pos[n + 1]
          sumz += pos[n + 2]
          cnt++
        }
        if (j > 0) {
          const n = (idx - N) * 3
          sumx += pos[n]
          sumy += pos[n + 1]
          sumz += pos[n + 2]
          cnt++
        }
        if (j < N - 1) {
          const n = (idx + N) * 3
          sumx += pos[n]
          sumy += pos[n + 1]
          sumz += pos[n + 2]
          cnt++
        }
        ax += coupling * (sumx - cnt * ox)
        ay += coupling * (sumy - cnt * oy)
        az += coupling * (sumz - cnt * oz)

        if (active) {
          const dx = pointer.x - (baseX[idx] + ox)
          const dy = pointer.y - (baseY[idx] + oy)
          const d = Math.sqrt(dx * dx + dy * dy)
          const tnorm = d * invR
          if (tnorm < 1) {
            const zBump = 1 - tnorm * tnorm
            az += force * zBump * zBump * 6.0
            if (d > 1e-4) {
              const pinch = tnorm * (1 - tnorm) * (1 - tnorm) * 6.75
              const dir = (force * pinch * 1.6) / d
              ax += dx * dir
              ay += dy * dir
            }
          }
        }

        accel[o3] = ax
        accel[o3 + 1] = ay
        accel[o3 + 2] = az
      }
    }

    for (let k = 0; k < nodeCount; k++) {
      const o3 = k * 3
      const nvx = (vel[o3] + accel[o3]) * retain
      const nvy = (vel[o3 + 1] + accel[o3 + 1]) * retain
      const nvz = (vel[o3 + 2] + accel[o3 + 2]) * retain
      vel[o3] = nvx
      vel[o3 + 1] = nvy
      vel[o3 + 2] = nvz

      let px = pos[o3] + nvx
      let py = pos[o3 + 1] + nvy
      let pz = pos[o3 + 2] + nvz
      if (px > 1.2) px = 1.2
      else if (px < -1.2) px = -1.2
      if (py > 1.2) py = 1.2
      else if (py < -1.2) py = -1.2
      if (pz > 1.2) pz = 1.2
      else if (pz < -1.2) pz = -1.2
      pos[o3] = px
      pos[o3 + 1] = py
      pos[o3 + 2] = pz
    }
  }

  /** 用相邻点的切线叉积重建法线，并计算是否已经静止，供渲染循环暂停判断。 */
  function commit(): { maxOffset: number; maxVelocity: number } {
    maxOffset = 0
    maxVel = 0
    for (let j = 0; j < N; j++) {
      for (let i = 0; i < N; i++) {
        const idx = j * N + i
        const o3 = idx * 3
        const iL = i > 0 ? idx - 1 : idx
        const iR = i < N - 1 ? idx + 1 : idx
        const iD = j > 0 ? idx - N : idx
        const iU = j < N - 1 ? idx + N : idx

        const lx = baseX[iL] + pos[iL * 3]
        const ly = baseY[iL] + pos[iL * 3 + 1]
        const lz = pos[iL * 3 + 2]
        const rx = baseX[iR] + pos[iR * 3]
        const ry = baseY[iR] + pos[iR * 3 + 1]
        const rz = pos[iR * 3 + 2]
        const dx = baseX[iD] + pos[iD * 3]
        const dy = baseY[iD] + pos[iD * 3 + 1]
        const dz = pos[iD * 3 + 2]
        const ux = baseX[iU] + pos[iU * 3]
        const uy = baseY[iU] + pos[iU * 3 + 1]
        const uz = pos[iU * 3 + 2]

        const txx = rx - lx
        const txy = ry - ly
        const txz = rz - lz
        const tyx = ux - dx
        const tyy = uy - dy
        const tyz = uz - dz

        let nx = txy * tyz - txz * tyy
        let ny = txz * tyx - txx * tyz
        let nz = txx * tyy - txy * tyx
        if (nz < 0) {
          nx = -nx
          ny = -ny
          nz = -nz
        }
        const len = Math.sqrt(nx * nx + ny * ny + nz * nz) || 1
        aNormal[o3] = nx / len
        aNormal[o3 + 1] = ny / len
        aNormal[o3 + 2] = nz / len

        aOffset[o3] = pos[o3]
        aOffset[o3 + 1] = pos[o3 + 1]
        aOffset[o3 + 2] = pos[o3 + 2]

        const om =
          Math.abs(pos[o3]) + Math.abs(pos[o3 + 1]) + Math.abs(pos[o3 + 2])
        if (om > maxOffset) maxOffset = om
        const vm =
          Math.abs(vel[o3]) + Math.abs(vel[o3 + 1]) + Math.abs(vel[o3 + 2])
        if (vm > maxVel) maxVel = vm
      }
    }
    return { maxOffset, maxVelocity: maxVel }
  }

  return {
    grid: aGrid,
    uv,
    offsets: aOffset,
    normals: aNormal,
    indices: index,
    step,
    commit,
    setAspect(value: number): void {
      aspect = value
      refreshBase()
    },
    /** 减少动画时清零位移和速度，法线恢复到平面。 */
    reset(): void {
      pos.fill(0)
      vel.fill(0)
      accel.fill(0)
      commit()
    },
  }
}
