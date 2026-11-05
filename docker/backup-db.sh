#!/bin/bash
# ============================================================
# 数据库备份脚本
#
# 用法：
#   手动执行：  bash docker/backup-db.sh
#   定时执行：  crontab -e  加一行（每天凌晨 3 点）：
#     0 3 * * * cd /path/to/lucky && bash docker/backup-db.sh >> logs/backup.log 2>&1
#
# 策略：
#   - 每天一个全量备份（mysqldump + gzip）
#   - 保留最近 30 天，自动清理更旧的
#   - 备份目录：./backups/db/
# ============================================================

set -euo pipefail

# ============ 配置 ============
BACKUP_DIR="$(dirname "$0")/../backups/db"
CONTAINER_NAME="lucky-mysql"
DB_NAME="lucky"
DB_USER="root"
DB_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"
KEEP_DAYS=30
DATE=$(date +%Y-%m-%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/${DB_NAME}_${DATE}.sql.gz"

# ============ 前置检查 ============
mkdir -p "$BACKUP_DIR"

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "[ERROR] 容器 ${CONTAINER_NAME} 未运行，备份失败"
    exit 1
fi

# ============ 执行备份 ============
echo "[$(date)] 开始备份数据库 ${DB_NAME}..."

docker exec "$CONTAINER_NAME" \
    mysqldump -u"$DB_USER" -p"$DB_PASSWORD" \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    --add-drop-table \
    --default-character-set=utf8mb4 \
    "$DB_NAME" 2>/dev/null | gzip > "$BACKUP_FILE"

# ============ 验证备份文件 ============
FILE_SIZE=$(stat -f%z "$BACKUP_FILE" 2>/dev/null || stat --printf="%s" "$BACKUP_FILE" 2>/dev/null || echo 0)
if [ "$FILE_SIZE" -lt 100 ]; then
    echo "[ERROR] 备份文件异常（${FILE_SIZE} bytes），可能备份失败"
    rm -f "$BACKUP_FILE"
    exit 1
fi

echo "[$(date)] 备份完成: ${BACKUP_FILE} ($(du -h "$BACKUP_FILE" | cut -f1))"

# ============ 清理旧备份 ============
echo "[$(date)] 清理 ${KEEP_DAYS} 天前的旧备份..."
find "$BACKUP_DIR" -name "${DB_NAME}_*.sql.gz" -mtime +"$KEEP_DAYS" -delete
REMAINING=$(ls -1 "$BACKUP_DIR"/${DB_NAME}_*.sql.gz 2>/dev/null | wc -l)
echo "[$(date)] 当前保留 ${REMAINING} 个备份文件"

# ============ 汇总 ============
echo "[$(date)] === 备份任务完成 ==="
