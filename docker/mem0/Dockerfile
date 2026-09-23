FROM python:3.11-slim

WORKDIR /app

# 替换 Debian 镜像源为阿里云源
RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
    sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list

# 安装系统基础依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# 使用阿里云 PyPI 高速镜像源安装依赖
RUN pip install --no-cache-dir -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com

COPY main.py .

EXPOSE 8888

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8888"]