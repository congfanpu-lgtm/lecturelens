# 幻灯片识别的实测脚本

`docs/benchmarks.md` 第 8 节("识别到底受什么限制")和第 10 节的数字是用这些脚本量的。都是一次性的小脚本,
在跑着媒体服务的那台机器上、`agentflow-media` 目录里用它的 venv 运行(`PYTHONPATH=. .venv/bin/python <脚本> …`),
CPU 占用读的是 `/proc/stat`(整机)。图片用的是某一讲已经留下来的幻灯片图(`<MEDIA_ROOT>/media/<key>/frames/*.jpg`)。

| 脚本 | 量什么 | 对应的表 |
|---|---|---|
| `bench_one.py '<glob>' <线程数> <cv2 线程数或 0> <空转 0/1>` | 一个引擎、一路调用:线程池大小和线程空转对吞吐和 CPU 的影响(前 60 张图) | 第 8 节第 1 张表 |
| `bench_layouts.py '<glob>' <threads/procs> <引擎数> <调用路数> <线程数> <空转 0/1>` | 不同摆法的吞吐:共用一个引擎、每路一个、每个进程一个(240 张图,每种摆法一个新进程) | 第 8 节第 2 张表 |
| `cpu_scaling.py` | 这台机器的核是不是"真的":纯计算和吃内存的循环,1、6、12、24 个同时跑 | 第 8 节第 3 张表 |
| `jpeg_quality.py <mediaKey>` | 候选帧存成 JPEG / PNG 对识别结果的影响(60 帧) | 第 10 节第 1 张表 |
| `replica_bench.py <mediaKey> <副本地址> …` | 1、2、4 个识别副本读同一讲的候选帧(先 `POST …/frames/pick`) | 第 10 节"加副本"那张表 |
| `ingest_many.py <名字> <讲次 id> …` | 几讲同时经引擎摄入:每讲多久可检索、每一步多久 | 第 10 节"几讲同时摄入"那张表 |

单讲的端到端数字是页面接口给的各步用时;强杀副本的演练是手工做的(步骤写在第 10 节),强杀 worker 的演练是
`scripts/chaos-course-ingest.sh`。
