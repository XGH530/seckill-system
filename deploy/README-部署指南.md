# 公网部署指南（阿里云轻量应用服务器）

> 本文档记录秒杀系统部署到公网的完整流程，以及**部署前的安全注意事项**。
> 目标地址：`http://<你的服务器IP>:8081`

---

## ⚠️ 一、先读这段：关于服务器登录信息

**不要把服务器 root 密码发在聊天记录里。** 一旦发出，它会留在对话历史、日志、剪贴板里，
而且很多人这个密码是复用的（比如和邮箱、其他平台相同）。

### 三个更安全的选择（按推荐度排序）

| 方式 | 怎么做 | 安全性 |
|---|---|---|
| **① 你自己执行（最推荐）** | 我把命令都写好，你复制粘贴到服务器终端。我不接触你的服务器 | 最高 |
| **② 临时改密码** | 你改一个新密码发我，部署完立刻改回去，并检查登录日志 | 中 |
| **③ 用密钥登录** | 阿里云控制台可以生成密钥对；比密码安全，但配置稍麻烦 | 高 |

本项目的部署步骤已经脚本化（`deploy/deploy.sh`），**你自己跑一遍只需要 3 条命令**，
我建议走方式 ①。

---

## 二、部署前检查清单

| 检查项 | 命令 / 位置 | 期望 |
|---|---|---|
| 服务器 IP | 阿里云控制台 → 轻量应用服务器 | 已知（现为 8.133.222.0） |
| 8080 是否已占用 | `ss -tlnp \| grep 8080` | 银行项目占用中 → **秒杀系统用 8081** |
| 安全组放行 | 控制台 → 防火墙 → 添加规则 | 放行 **TCP 8081** |
| JDK 版本 | `java -version` | 17 或更高 |
| Redis 状态 | `systemctl status redis` | active (running) |

---

## 三、部署步骤（方式 ①，你自己执行）

### 第 1 步：本地打包并上传

在**你的 Windows 电脑**上（Git Bash）：

```bash
cd "C:/Users/谁知青杉/WorkBuddy/2026-09-18-09-07-45/projects/seckill-system"

# 打包（用项目自带的 mvnbuild.bat 包装脚本）
# 注意：如果本地应用正在运行，先停掉，否则 jar 被锁无法覆盖
cmd //c "C:\Users\谁知青杉\tools\mvnbuild.bat" clean package -DskipTests

# 上传到服务器（把 <IP> 换成你的服务器 IP）
scp target/seckill-system-1.0.0.jar root@<IP>:/opt/seckill/
scp deploy/deploy.sh root@<IP>:/opt/seckill/
```

> 如果 `/opt/seckill/` 不存在，先执行 `ssh root@<IP> "mkdir -p /opt/seckill"`

### 第 2 步：服务器上执行部署脚本

```bash
ssh root@<IP>
cd /opt/seckill
chmod +x deploy.sh
bash deploy.sh
```

脚本会自动完成：环境检查 → 端口检查 → 停止旧实例 → 后台启动（8081）→ 健康检查。

### 第 3 步：放行安全组端口

阿里云控制台 → 轻量应用服务器 → 你的实例 → **防火墙** → 添加规则：

```
应用类型: 自定义
协议:     TCP
端口范围: 8081
备注:     秒杀系统演示
```

**这一步不做，公网就访问不到**（最常见的部署失败原因）。

### 第 4 步：验证

```bash
# 本地浏览器访问
http://<IP>:8081/          # 演示主页
http://<IP>:8081/doc.html  # 接口文档
```

---

## 四、日常运维命令

```bash
# 查看日志
tail -f /opt/seckill/app.log

# 查看进程
ps -ef | grep seckill

# 停止服务
kill $(cat /opt/seckill/app.pid)

# 重启（重新上传 jar 后再执行）
bash /opt/seckill/deploy.sh

# 端口占用排查
ss -tlnp | grep 8081
```

---

## 五、部署后的注意事项

### 1. demo profile 的数据是临时的

应用使用 **H2 内存数据库**，**重启后所有订单和库存数据都会重置**。
这对演示来说其实是优点（每次都是干净状态），但你要知道：

- 面试官访问时看到的是重置后的全新状态，库存充足
- 如果想让数据持久化，需要切换 `application.yml` 中的数据库配置到 MySQL

### 2. 演示前先预热

应用启动后库存需要预热到 Redis，脚本已自动处理；
如果手动重启，记得调一次：

```bash
curl -X POST http://localhost:8081/admin/warmup/all
```

### 3. 服务器资源占用

`-Xms256m -Xmx512m`，对轻量应用服务器（通常 2G 内存）很友好，
与银行项目（8080）可以并存。如果服务器内存紧张（1G 以下），
两个 Java 应用同时跑会比较吃力，建议错峰演示。

### 4. 简历链接的诚实性

部署成功后，简历 `线上演示` 行才成立：

```
线上演示：http://<IP>:8081（账号：zhangsan/123456）
```

**投递前务必重新访问一次确认可访问。** 服务器到期、实例停机都会让链接失效。

---

## 六、可选：绑定域名 + Nginx 反代（进阶）

如果想让链接更好看（比如 `http://seckill.你的域名.com`），可以：

1. 买一个便宜域名并解析到服务器 IP
2. 用 Nginx 反代 8081：

```nginx
server {
    listen 80;
    server_name seckill.example.com;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

3. `nginx -t && systemctl reload nginx`

> 服务器 80 端口目前返回 403，说明已有 Nginx 在跑，可以在此基础上添加配置。
> 这属于加分项，不是必须——**先把 8081 直连跑通更重要**。
