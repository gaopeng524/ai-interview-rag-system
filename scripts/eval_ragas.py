import sys
import os

sys.stdout.reconfigure(encoding='utf-8')

try:
    import langchain_google_vertexai
    sys.modules['langchain_community.chat_models.vertexai'] = langchain_google_vertexai
except Exception:
    pass

import numpy as np
import pandas as pd
from openai import OpenAI
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import (
    LLMContextRecall,
    ContextPrecision,
    Faithfulness,
    AnswerRelevancy
)
from ragas.llms import llm_factory
from langchain_openai import OpenAIEmbeddings as LangchainOpenAIEmbeddings
from ragas.embeddings import LangchainEmbeddingsWrapper
from langchain_text_splitters import RecursiveCharacterTextSplitter

api_key = os.environ.get("DASHSCOPE_API_KEY")
if not api_key:
    raise ValueError("未找到 DASHSCOPE_API_KEY 环境变量！")

base_url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
openai_client = OpenAI(api_key=api_key, base_url=base_url)

evaluator_llm = llm_factory(model="qwen-turbo", client=openai_client)
lc_embeddings = LangchainOpenAIEmbeddings(model="text-embedding-v3", api_key=api_key, base_url=base_url, dimensions=1024)
evaluator_embeddings = LangchainEmbeddingsWrapper(lc_embeddings)

long_document = """
【第一章：JUC 锁体系与 AQS 深度解密】
AQS (AbstractQueuedSynchronizer) 是 Java 并发包的基石。AQS 底层维护一个 volatile int state 同步状态变量，所有对 state 的修改都必须通过 Unsafe 提供的 CAS 操作保证原子性。在独占锁 ReentrantLock 中，state=0 表示锁处于空闲，state>0 表示重入次数。
当并发抢锁失败时，AQS 会将当前线程封装为一个 Node 节点加入底层的双向 FIFO 同步等待队列中，并通过 LockSupport.park() 将线程挂起进入 WAITING 阻塞状态。
在锁释放流程中，线程调用 tryRelease 将 state 归零；随后由头节点 Head 调用 unparkSuccessor 唤醒等待队列中首个处于有效等待状态的后继 Node 节点。被唤醒的线程重新在 acquireQueued 自旋循环中通过 CAS 尝试竞争 state。

【第二章：高并发 Redis 缓存体系三大问题实战治理】
缓存雪崩是指大量 Key 在同一时刻大面积集中过期失效，瞬间导致海量瞬时流量穿透至后端 MySQL 数据库。工业级治理方案：1. 在基础过期时间上增加随机抖动扰动值 (TTL Jitter, 如 5~10 分钟偏差)，杜绝集体失效；2. 构建多级缓存架构，在应用层引入 Caffeine 本地二级缓存建立前置防线。
缓存击穿是指单一具有超高并发请求的 HotKey 突然过期失效。治理方案：1. 使用 Redisson 分布式互斥锁 (Mutex Lock)，仅放行单个获得锁的线程去查库回填缓存，其余线程自旋重试；2. 物理永不过期，设置逻辑过期时间并由线程池异步在后台执行刷新。
缓存穿透是指查询一个数据库中根本不存在的非法 Key。治理方案：缓存空值 (Null Value) 设置 30 秒短过期时间，或在网关层部署 Bloom Filter (布隆过滤器) 进行 O(k) 复杂度的高效拦截。

【第三章：MySQL InnoDB MVCC 多版本并发控制底层】
InnoDB 存储引擎基于 Undo Log 版本链与 ReadView 实现非阻塞快照读。每行记录包含隐藏的 DB_TRX_ID (事务ID) 与 DB_ROLL_PTR (回滚指针)。
在 RC (Read Committed) 读已提交隔离级别下，同一个事务中的每一次 SELECT 查询都会重新生成一个最新的 ReadView，保证读到最新提交的数据。
在 RR (Repeatable Read) 可重复读隔离级别下，事务仅在第一次执行 SELECT 时生成唯一的 ReadView，后续查询均复用该 ReadView，彻底解决不可重复读问题。
针对当前读 (SELECT ... FOR UPDATE 或 UPDATE)，InnoDB 依靠 Next-Key Lock (行锁 Record Lock + 间隙锁 Gap Lock 的组合) 锁定记录及其前后开闭区间间隙，杜绝其他事务并发插入产生幻读。
"""

print("📚 [1/3] 正在对真实长文档进行切片与向量检索...")
splitter = RecursiveCharacterTextSplitter(chunk_size=250, chunk_overlap=40)
chunks = splitter.split_text(long_document)

def get_embedding(text):
    resp = openai_client.embeddings.create(input=[text], model="text-embedding-v3", dimensions=1024)
    return resp.data[0].embedding

chunk_vectors = [get_embedding(c) for c in chunks]

def cosine_sim(v1, v2):
    return np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2))

test_qa = [
    {
        "question": "在超高并发场景下，Redis 缓存雪崩与击穿分别该如何治理？本地二级缓存与分布式锁怎么配合？",
        "ground_truth": "缓存雪崩治理需在基础 TTL 上增加随机扰动 (TTL Jitter) 并搭建 Caffeine 本地二级缓存；缓存击穿治理需使用 Redisson 分布式互斥锁保证单请求查库，或设置逻辑过期时间通过线程池异步刷新。"
    },
    {
        "question": "AQS 的 state 变量是如何更新的？抢锁失败后线程是如何排队和被唤醒的？",
        "ground_truth": "AQS 底层通过 Unsafe 提供的 CAS 原子更新 volatile int state 同步状态变量。抢锁失败后封装为 Node 放入双向 FIFO 同步队列并通过 LockSupport.park() 阻塞，释放锁时调用 unparkSuccessor 唤醒头节点后继的有效 WAITING 线程重新竞争。"
    },
    {
        "question": "MySQL InnoDB 的 MVCC 在 RC 与 RR 隔离级别下的 ReadView 生成机制有何不同？如何解决幻读？",
        "ground_truth": "RC 隔离级别下每次 SELECT 都会重新生成新的 ReadView；RR 隔离级别下事务第一次 SELECT 时生成唯一的 ReadView。针对当前读依靠 Next-Key Lock (行锁 + 间隙锁) 锁定区间解决幻读。"
    }
]

eval_questions = []
eval_contexts = []
eval_answers = []
eval_ground_truths = []

for item in test_qa:
    q = item["question"]
    gt = item["ground_truth"]
    q_vec = get_embedding(q)
    
    sims = [cosine_sim(q_vec, cv) for cv in chunk_vectors]
    top_indices = np.argsort(sims)[-3:][::-1]
    retrieved_chunks = [chunks[i] for i in top_indices]
    
    prompt = f"你是一个大厂资深架构师。请严格根据以下检索到的切片内容精准回答问题：\n\n【参考切片】：\n{' '.join(retrieved_chunks)}\n\n【问题】：{q}"
    resp = openai_client.chat.completions.create(
        model="qwen-turbo",
        messages=[{"role": "user", "content": prompt}]
    )
    answer = resp.choices[0].message.content
    
    eval_questions.append(q)
    eval_contexts.append(retrieved_chunks)
    eval_answers.append(answer)
    eval_ground_truths.append(gt)

eval_dataset = Dataset.from_dict({
    "user_input": eval_questions,
    "retrieved_contexts": eval_contexts,
    "response": eval_answers,
    "reference": eval_ground_truths
})

print("⚖️  [2/3] 正在启动 Ragas 官方 4 维量化数学评估...")
results = evaluate(
    dataset=eval_dataset,
    metrics=[
        LLMContextRecall(llm=evaluator_llm),
        ContextPrecision(llm=evaluator_llm),
        Faithfulness(llm=evaluator_llm),
        AnswerRelevancy(llm=evaluator_llm, embeddings=evaluator_embeddings)
    ]
)

print("\n=========================================================================================================")
print("                                 🏆 Ragas 官方权威真机量化评测大盘")
print("=========================================================================================================")
df = results.to_pandas()
cols = ["user_input", "context_recall", "context_precision", "faithfulness", "answer_relevancy"]
existing_cols = [c for c in cols if c in df.columns]
print(df[existing_cols].to_string(index=False))
print("=========================================================================================================\n")