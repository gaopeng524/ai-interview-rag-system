import os
import logging
from typing import List, Optional, Dict, Any
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("mem0-service")

app = FastAPI(title="Mem0 Universal Memory Service", version="1.0.0")

# 从环境变量读取配置
PG_HOST = os.getenv("PG_HOST", "postgres")
PG_PORT = int(os.getenv("PG_PORT", "5432"))
PG_USER = os.getenv("PG_USER", "postgres")
PG_PASSWORD = os.getenv("PG_PASSWORD", "123456")
PG_DATABASE = os.getenv("PG_DATABASE", "ai_chat")

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_API_BASE = os.getenv("OPENAI_API_BASE", "https://dashscope.aliyuncs.com/compatible-mode/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "qwen-turbo")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-v3")

memory_client = None

def get_memory_client():
    global memory_client
    if memory_client is None:
        try:
            from mem0 import Memory
            config = {
                "vector_store": {
                    "provider": "pgvector",
                    "config": {
                        "host": PG_HOST,
                        "port": PG_PORT,
                        "user": PG_USER,
                        "password": PG_PASSWORD,
                        "dbname": PG_DATABASE,
                        "collection_name": "mem0_memories",
                        "embedding_model_dims": 1536
                    }
                },
                "llm": {
                    "provider": "openai",
                    "config": {
                        "model": LLM_MODEL,
                        "api_key": OPENAI_API_KEY if OPENAI_API_KEY else "sk-dummy-key",
                        "openai_base_url": OPENAI_API_BASE
                    }
                },
                "embedder": {
                    "provider": "openai",
                    "config": {
                        "model": EMBEDDING_MODEL,
                        "api_key": OPENAI_API_KEY if OPENAI_API_KEY else "sk-dummy-key",
                        "openai_base_url": OPENAI_API_BASE
                    }
                }
            }
            memory_client = Memory.from_config(config)
            logger.info("Mem0 Memory client successfully initialized with pgvector backend.")
        except Exception as e:
            logger.warning(f"Failed to initialize Mem0 client: {e}. Running in lightweight fallback mode.")
            memory_client = None
    return memory_client

# 内存轻量备份字典（以防数据库未就绪时也能保证服务响应）
fallback_store: Dict[str, List[Dict[str, Any]]] = {}

class MessageItem(BaseModel):
    role: str = "user"
    content: str

class AddMemoryRequest(BaseModel):
    messages: Optional[List[MessageItem]] = None
    text: Optional[str] = None
    user_id: str
    metadata: Optional[Dict[str, Any]] = None

class SearchMemoryRequest(BaseModel):
    query: str
    user_id: str
    limit: Optional[int] = 5

class MemoryResponse(BaseModel):
    id: str
    memory: str
    user_id: Optional[str] = None
    created_at: Optional[str] = None

@app.get("/health")
def health():
    return {"status": "ok", "service": "mem0-service"}

@app.post("/v1/memories/")
def add_memory(req: AddMemoryRequest):
    content = req.text
    if not content and req.messages:
        content = "\n".join([f"{m.role}: {m.content}" for m in req.messages])
    
    if not content:
        raise HTTPException(status_code=400, detail="Either 'text' or 'messages' must be provided")

    client = get_memory_client()
    if client:
        try:
            result = client.add(content, user_id=req.user_id, metadata=req.metadata or {})
            logger.info(f"Added memory for user {req.user_id}: {result}")
            return {"status": "success", "result": result}
        except Exception as e:
            logger.error(f"Mem0 add failed: {e}")

    # Fallback 内存存储
    if req.user_id not in fallback_store:
        fallback_store[req.user_id] = []
    fallback_store[req.user_id].append({
        "id": f"mem_{len(fallback_store[req.user_id]) + 1}",
        "memory": content,
        "user_id": req.user_id
    })
    return {"status": "success", "mode": "fallback", "memory": content}

@app.post("/v1/memories/search/")
def search_memory(req: SearchMemoryRequest):
    client = get_memory_client()
    if client:
        try:
            results = client.search(req.query, user_id=req.user_id, limit=req.limit or 5)
            logger.info(f"Search memory for user {req.user_id}, results count: {len(results)}")
            # 兼容字典列表与对象列表
            formatted = []
            for r in results:
                if isinstance(r, dict):
                    formatted.append(r.get("memory", str(r)))
                else:
                    formatted.append(getattr(r, "memory", str(r)))
            return formatted
        except Exception as e:
            logger.error(f"Mem0 search failed: {e}")

    # Fallback 内存检索
    user_mems = fallback_store.get(req.user_id, [])
    matched = [m["memory"] for m in user_mems if any(w in m["memory"] for w in req.query.split())]
    if not matched and user_mems:
        matched = [m["memory"] for m in user_mems[:req.limit or 5]]
    return matched

@app.get("/v1/memories/{user_id}")
def get_all_memories(user_id: str):
    client = get_memory_client()
    if client:
        try:
            return client.get_all(user_id=user_id)
        except Exception as e:
            logger.error(f"Mem0 get_all failed: {e}")
    return fallback_store.get(user_id, [])

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8888)
