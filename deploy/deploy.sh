#!/usr/bin/env bash
# =====================================================================
#  秒杀系统 —— 阿里云服务器一键部署脚本
# =====================================================================
#  用法：在服务器上以 root 执行
#     bash deploy.sh
#
#  脚本做的事：
#     1. 检查/安装 JDK 17+（缺失则提示安装命令）
#     2. 检查/安装 Redis（缺失则提示安装命令）
#     3. 创建应用目录 /opt/seckill
#     4. 停止旧实例（若在跑）
#     5. 后台启动新实例（8081 端口，避免与银行项目 8080 冲突）
#     6. 等待健康检查通过
#
#  【为什么用独立端口 8081】
#     服务器上 8080 已被「银行记账与理财系统」占用。
#     两个项目各占一个端口，演示环境可同时提供两个入口。
# =====================================================================

set -e

APP_NAME="seckill-system"
APP_DIR="/opt/seckill"
JAR_NAME="seckill-system-1.0.0.jar"
PORT=8081
LOG_FILE="${APP_DIR}/app.log"
PID_FILE="${APP_DIR}/app.pid"

echo "=================================================="
echo " 秒杀系统部署  $(date '+%Y-%m-%d %H:%M:%S')"
echo " 安装目录: ${APP_DIR}   端口: ${PORT}"
echo "=================================================="

# ---------- 1. 环境检查 ----------
echo ""
echo "[1/6] 检查运行环境..."

if ! command -v java >/dev/null 2>&1; then
    echo "  ✗ 未安装 Java。请先执行（CentOS/AliyunLinux）:"
    echo "      yum install -y java-17-openjdk java-17-openjdk-devel"
    echo "    或（Ubuntu/Debian）:"
    echo "      apt update && apt install -y openjdk-17-jdk"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1)
echo "  ✓ Java: ${JAVA_VER}"

if command -v redis-server >/dev/null 2>&1 || systemctl is-active --quiet redis 2>/dev/null; then
    echo "  ✓ Redis 已安装"
else
    echo "  ✗ 未检测到 Redis，尝试启动..."
    systemctl start redis 2>/dev/null || systemctl start redis-server 2>/dev/null || {
        echo "    未安装 Redis，请先执行: yum install -y redis  然后 systemctl enable --now redis"
        exit 1
    }
fi
systemctl is-active --quiet redis 2>/dev/null && echo "  ✓ Redis 运行中" || echo "  ! Redis 状态未知，请确认已启动"

# ---------- 2. 端口占用检查 ----------
echo ""
echo "[2/6] 检查端口 ${PORT}..."
if ss -tlnp 2>/dev/null | grep -q ":${PORT} "; then
    echo "  ! 端口 ${PORT} 已被占用，将先停止旧进程"
else
    echo "  ✓ 端口 ${PORT} 可用"
fi

# ---------- 3. 准备目录 ----------
echo ""
echo "[3/6] 准备安装目录..."
mkdir -p "${APP_DIR}"
if [ ! -f "${APP_DIR}/${JAR_NAME}" ]; then
    echo "  ✗ 未找到 jar 包: ${APP_DIR}/${JAR_NAME}"
    echo "    请先用 scp 上传（在本地执行）:"
    echo "      scp target/${JAR_NAME} root@<服务器IP>:${APP_DIR}/"
    exit 1
fi
echo "  ✓ jar 包就位: $(du -h ${APP_DIR}/${JAR_NAME} | cut -f1)"

# ---------- 4. 停止旧实例 ----------
echo ""
echo "[4/6] 停止旧实例..."
if [ -f "${PID_FILE}" ]; then
    OLD_PID=$(cat "${PID_FILE}")
    if kill -0 "${OLD_PID}" 2>/dev/null; then
        kill "${OLD_PID}"
        echo "  已停止 PID ${OLD_PID}，等待退出..."
        for i in $(seq 1 15); do
            kill -0 "${OLD_PID}" 2>/dev/null || break
            sleep 1
        done
        kill -9 "${OLD_PID}" 2>/dev/null || true
    fi
    rm -f "${PID_FILE}"
fi
# 兜底：按端口清理残留进程
PID_BY_PORT=$(ss -tlnp 2>/dev/null | grep ":${PORT} " | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "${PID_BY_PORT}" ]; then
    kill -9 "${PID_BY_PORT}" 2>/dev/null || true
    echo "  已清理端口占用进程 ${PID_BY_PORT}"
fi
echo "  ✓ 旧实例已清理"

# ---------- 5. 启动应用 ----------
echo ""
echo "[5/6] 启动应用（demo profile，内存 H2，无需 MySQL）..."
cd "${APP_DIR}"

# 【关键参数】
#   spring.profiles.active=demo  使用 H2 内存库 + 内置内存队列，零外部依赖
#   --server.port=${PORT}         8081，避开银行项目的 8080
#   sentinel.eager=false          懒加载 Sentinel，加快启动
nohup java -Dfile.encoding=UTF-8 \
    -Xms256m -Xmx512m \
    -jar "${JAR_NAME}" \
    --spring.profiles.active=demo \
    --server.port=${PORT} \
    --spring.cloud.sentinel.eager=false \
    > "${LOG_FILE}" 2>&1 &

NEW_PID=$!
echo "${NEW_PID}" > "${PID_FILE}"
echo "  ✓ 已启动，PID=${NEW_PID}"

# ---------- 6. 健康检查 ----------
echo ""
echo "[6/6] 等待服务就绪（最多 60 秒）..."
READY=0
for i in $(seq 1 30); do
    sleep 2
    if grep -q "Started SeckillApplication" "${LOG_FILE}" 2>/dev/null; then
        READY=1
        break
    fi
    if ! kill -0 "${NEW_PID}" 2>/dev/null; then
        echo "  ✗ 进程已退出，日志尾部："
        tail -30 "${LOG_FILE}"
        exit 1
    fi
done

if [ "${READY}" -eq 1 ]; then
    echo "  ✓ 应用启动成功"
    echo ""
    echo "=================================================="
    echo " 部署完成"
    echo "=================================================="
    echo " 演示页:    http://<服务器IP>:${PORT}/"
    echo " 接口文档:  http://<服务器IP>:${PORT}/doc.html"
    echo " 健康检查:  http://<服务器IP>:${PORT}/actuator/health"
    echo " 测试账号:  zhangsan / 123456"
    echo ""
    echo " 查看日志:  tail -f ${LOG_FILE}"
    echo " 停止服务:  kill \$(cat ${PID_FILE})"
    echo ""
    echo " 安全组提醒: 阿里云控制台需放行 TCP ${PORT} 端口"
    echo "=================================================="
else
    echo "  ! 等待超时，请检查日志：tail -50 ${LOG_FILE}"
    exit 1
fi
