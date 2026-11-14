"""Does this machine really have 24 cores' worth of compute? The same job in 1, 6, 12 and 24 processes at once:
a pure-CPU loop (no memory traffic) and a memory-heavy numpy loop. Average seconds per job; flat = cores are real."""
import multiprocessing as mp
import time


def cpu(n):
    t, x = time.time(), 0
    for i in range(n):
        x += i * i % 7
    return time.time() - t


def mem(n):
    import numpy as np
    a = np.random.rand(3000, 3000)   # 72 MB, far larger than the cache
    t = time.time()
    for _ in range(n):
        a = a * 1.0001 + 0.1
    return time.time() - t


if __name__ == "__main__":
    for k in (1, 6, 12, 24):
        with mp.Pool(k) as p:
            c = p.map(cpu, [15_000_000] * k)
            m = p.map(mem, [40] * k)
        print(f"{k:2d} at once | pure CPU {sum(c) / k:.2f} s per job | memory-heavy {sum(m) / k:.2f} s per job", flush=True)
