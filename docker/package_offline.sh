#!/bin/bash

# ==============================================================================
# 脚本名称: docker_offline_packer.sh (可自行重命名)
# 功能描述: 解析 docker-compose.yml，交互式拉取并打包 Docker 镜像，方便离线部署。
#
# 使用说明:
# 1. 准备: 将此脚本放置在包含 docker-compose.yml (或 .yaml) 的目录中。
# 2. 授权: 首次使用需赋予执行权限，执行命令: chmod +x docker_offline_packer.sh
# 3. 运行: 执行命令: ./docker_offline_packer.sh
#
# 核心特性:
# - 🏗️ 架构选择: 支持跨平台拉取目标架构镜像 (如 linux/amd64 或 linux/arm64)。
# - 📋 自动解析: 自动读取 docker-compose 配置文件并提取唯一镜像列表；
#   对含 build 的服务，解析其 Dockerfile 中的 FROM 基镜像（替代本地构建产物镜像名）。
# - 🎯 灵活选择: 支持按序号选择需要打包的镜像（逗号分隔），或直接回车全选。
# - ⚡ 本地优先: 可选择跳过全局拉取，优先使用本地已存在的镜像，节省网络与时间。
# - 💾 多种导出: 支持合并为一个单个 .tar 文件，或按镜像分别导出到一个新建目录。
# - 🚀 导入指南: 任务完成后，会自动打印适用于离线服务器的具体导入命令。
# ==============================================================================

# 定义颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ===========================
# 0. 选择 compose yaml 文件
# ===========================
# 支持通过第一个参数直接指定文件，例如：
# ./package_offline.sh docker-compose-init.yml
COMPOSE_FILE_ARG="$1"
COMPOSE_FILE=""

if [ -n "$COMPOSE_FILE_ARG" ]; then
    if [ -f "$COMPOSE_FILE_ARG" ]; then
        COMPOSE_FILE="$COMPOSE_FILE_ARG"
    else
        echo -e "${RED}❌ 错误：指定的文件不存在: $COMPOSE_FILE_ARG${NC}"
        exit 1
    fi
else
    COMPOSE_FILES=($(ls -1 docker-compose*.yml docker-compose*.yaml 2>/dev/null | sort -u))
    if [ ${#COMPOSE_FILES[@]} -eq 0 ]; then
        echo -e "${RED}❌ 错误：当前目录下未找到 docker-compose*.yml/.yaml 文件${NC}"
        exit 1
    elif [ ${#COMPOSE_FILES[@]} -eq 1 ]; then
        COMPOSE_FILE="${COMPOSE_FILES[0]}"
    else
        echo -e "${CYAN}📄 请选择要使用的 Compose 文件:${NC}"
        i=1
        for file in "${COMPOSE_FILES[@]}"; do
            echo -e "${YELLOW}$i.${NC} $file"
            ((i++))
        done
        read -p "请输入序号 [1-${#COMPOSE_FILES[@]}, 默认 1]: " FILE_CHOICE
        FILE_CHOICE=${FILE_CHOICE:-1}

        if ! [[ "$FILE_CHOICE" =~ ^[0-9]+$ ]] || [ "$FILE_CHOICE" -lt 1 ] || [ "$FILE_CHOICE" -gt ${#COMPOSE_FILES[@]} ]; then
            echo -e "${RED}❌ 错误：无效选择: $FILE_CHOICE${NC}"
            exit 1
        fi

        COMPOSE_FILE="${COMPOSE_FILES[$((FILE_CHOICE-1))]}"
    fi
fi

echo -e "✅ 已选择 Compose 文件: ${GREEN}${COMPOSE_FILE}${NC}"
echo "----------------------------------------"

# ===========================
# 1. 选择架构
# ===========================
echo -e "${CYAN}🏗️  请选择目标镜像架构:${NC}"
echo "1) linux/amd64 (常见的 x86 服务器)"
echo "2) linux/arm64 (Apple Silicon, 树莓派等)"
read -p "请输入选项 [1-2, 默认 1]: " ARCH_CHOICE

case $ARCH_CHOICE in
    2)
        PLATFORM="linux/arm64"
        ARCH_NAME="arm64"
        EXPECTED_OS="linux"
        EXPECTED_ARCH="arm64"
        ;;
    *)
        PLATFORM="linux/amd64"
        ARCH_NAME="amd64"
        EXPECTED_OS="linux"
        EXPECTED_ARCH="amd64"
        ;;
esac

echo -e "✅ 已选择架构: ${GREEN}${PLATFORM}${NC}"
echo "----------------------------------------"

# 解析 compose：普通服务用 image；含 build 的服务改用 Dockerfile 基镜像
collect_compose_images() {
    local compose_file="$1"
    local compose_dir
    compose_dir="$(cd "$(dirname "$compose_file")" && pwd)"
    local compose_abs="${compose_dir}/$(basename "$compose_file")"

    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${YELLOW}⚠️  未找到 python3，无法解析 build 服务 Dockerfile，回退为 config --images。${NC}" >&2
        docker compose -f "$compose_abs" config --images | sort -u | grep -v "^$"
        return
    fi

    python3 - "$compose_abs" "$compose_dir" <<'PY'
import json
import os
import re
import subprocess
import sys

compose_file, compose_dir = sys.argv[1], sys.argv[2]

def extract_from_images(dockerfile):
    images = []
    seen = set()
    with open(dockerfile, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split("#", 1)[0].strip()
            if not re.match(r"(?i)^FROM\s+", line):
                continue
            line = re.sub(r"(?i)^FROM\s+", "", line)
            while re.match(r"^--\S+\s+", line):
                line = re.sub(r"^--\S+\s+", "", line, count=1)
            line = re.sub(r"(?i)\s+AS\s+.*", "", line).strip()
            if not line or line.lower() == "scratch":
                continue
            key = line.lower()
            if key not in seen:
                seen.add(key)
                images.append(line)
    return images

def resolve_dockerfile(build):
    if isinstance(build, str):
        context = build if os.path.isabs(build) else os.path.normpath(os.path.join(compose_dir, build))
        return os.path.join(context, "Dockerfile")
    context = build.get("context", ".")
    if not os.path.isabs(context):
        context = os.path.normpath(os.path.join(compose_dir, context))
    dockerfile = build.get("dockerfile", "Dockerfile")
    return os.path.join(context, dockerfile)

config = json.loads(
    subprocess.run(
        ["docker", "compose", "-f", compose_file, "config", "--format", "json"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
)

build_service_images = set()
dockerfile_base_images = []

for service in config.get("services", {}).values():
    build = service.get("build")
    if not build:
        continue
    image = service.get("image")
    if image:
        build_service_images.add(image)
    dockerfile = resolve_dockerfile(build)
    if not os.path.isfile(dockerfile):
        print(f"WARN: Dockerfile not found: {dockerfile}", file=sys.stderr)
        continue
    dockerfile_base_images.extend(extract_from_images(dockerfile))

all_images = [
    line.strip()
    for line in subprocess.run(
        ["docker", "compose", "-f", compose_file, "config", "--images"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.splitlines()
    if line.strip()
]

final_images = []
seen = set()

for image in all_images:
    if image in build_service_images:
        continue
    key = image.lower()
    if key in seen:
        continue
    seen.add(key)
    final_images.append((image, False))

for image in dockerfile_base_images:
    key = image.lower()
    if key in seen:
        continue
    seen.add(key)
    final_images.append((image, True))

for image, is_build in final_images:
    print(f"{image}\t{1 if is_build else 0}")
PY
}

# ===========================
# 2. 读取并列出镜像 (自动去重)
# ===========================
echo "🔍 正在读取镜像列表 (自动去重)..."

ALL_IMAGES=()
ALL_IMAGE_LABELS=()
while IFS=$'\t' read -r image is_build; do
    if [ -z "$image" ]; then
        continue
    fi
    ALL_IMAGES+=("$image")
    if [ "$is_build" = "1" ]; then
        ALL_IMAGE_LABELS+=("$image (build)")
    else
        ALL_IMAGE_LABELS+=("$image")
    fi
done < <(collect_compose_images "$COMPOSE_FILE")

if [ ${#ALL_IMAGES[@]} -eq 0 ]; then
    echo -e "${RED}❌ 错误：未能读取到任何镜像列表。${NC}"
    exit 1
fi

echo -e "${CYAN}📋 发现以下唯一镜像：${NC}"
i=1
for label in "${ALL_IMAGE_LABELS[@]}"; do
    echo -e "${YELLOW}$i.${NC} $label"
    ((i++))
done

# ===========================
# 3. 选择要打包的镜像
# ===========================
echo ""
echo -e "${CYAN}👉 请输入要打包的镜像序号${NC}"
echo "   - 用逗号分隔 (例如: 1,3,5)"
echo "   - 支持中文逗号"
echo "   - 直接回车 = 打包所有"
read -p "请输入: " SELECTION

TARGET_IMAGES=""

if [ -z "$SELECTION" ]; then
    echo "👉 未输入序号，默认选中 **所有** 镜像。"
    TARGET_IMAGES="${ALL_IMAGES[@]}"
else
    # 替换中文逗号为英文逗号，再将逗号换为空格
    SELECTION=${SELECTION//，/,}
    SELECTION_SPACES=${SELECTION//,/ }

    echo "👉 正在解析选择..."

    for index in $SELECTION_SPACES; do
        # 检查是否为数字
        if ! [[ "$index" =~ ^[0-9]+$ ]]; then
            echo -e "${RED}⚠️  跳过非数字输入: $index${NC}"
            continue
        fi

        actual_index=$((index-1))

        # 检查序号是否有效
        if [ -n "${ALL_IMAGES[$actual_index]}" ]; then
            TARGET_IMAGES="$TARGET_IMAGES ${ALL_IMAGES[$actual_index]}"
        else
            echo -e "${RED}⚠️  警告：序号 $index 超出范围，已跳过${NC}"
        fi
    done
fi

if [ -z "$TARGET_IMAGES" ]; then
     echo -e "${RED}❌ 错误：未选中任何有效镜像，退出。${NC}"
     exit 1
fi

# ===========================
# 4. 最终确认与拉取选项
# ===========================
image_display_label() {
    local img="$1"
    local idx
    for idx in "${!ALL_IMAGES[@]}"; do
        if [ "${ALL_IMAGES[$idx]}" = "$img" ]; then
            echo "${ALL_IMAGE_LABELS[$idx]}"
            return
        fi
    done
    echo "$img"
}

image_platform() {
    local img="$1"
    docker image inspect --format '{{.Os}}/{{.Architecture}}{{if .Variant}}/{{.Variant}}{{end}}' "$img" 2>/dev/null
}

image_matches_platform() {
    local img="$1"
    local os
    local arch
    os="$(docker image inspect --format '{{.Os}}' "$img" 2>/dev/null)"
    arch="$(docker image inspect --format '{{.Architecture}}' "$img" 2>/dev/null)"

    [ "$os" = "$EXPECTED_OS" ] && [ "$arch" = "$EXPECTED_ARCH" ]
}

pull_image_for_platform() {
    local img="$1"
    local img_label
    img_label="$(image_display_label "$img")"

    echo -e "⏳ [正在拉取] $img_label (架构: $PLATFORM) ..."
    if ! docker pull --platform "$PLATFORM" "$img"; then
        echo -e "${RED}❌ 拉取失败: $img_label，将跳过打包该镜像。${NC}"
        return 1
    fi

    if image_matches_platform "$img"; then
        echo -e "${GREEN}✅ 架构校验通过: $img_label ($(image_platform "$img"))${NC}"
        return 0
    fi

    echo -e "${RED}❌ 架构校验失败: $img_label，本地实际为 $(image_platform "$img")，期望 $PLATFORM，将跳过打包该镜像。${NC}"
    return 1
}

save_image_for_platform() {
    local img="$1"
    local output_file="$2"

    if docker save --help 2>&1 | grep -q -- '--platform'; then
        docker save --platform "$PLATFORM" -o "$output_file" "$img"
    else
        docker save -o "$output_file" "$img"
    fi
}

echo "----------------------------------------"
echo -e "${CYAN}📦 准备打包清单:${NC}"
for img in $TARGET_IMAGES; do
    echo -e "   - ${GREEN}$(image_display_label "$img")${NC}"
done
echo "----------------------------------------"

read -p "是否跳过全局拉取过程，优先使用本地已有镜像？ (y/n) [默认 y]: " SKIP_PULL
SKIP_PULL=${SKIP_PULL:-y}

if [[ "$SKIP_PULL" == "y" || "$SKIP_PULL" == "Y" ]]; then
    echo -e "✅ 已选择 ${GREEN}跳过全局拉取${NC}，将优先使用本地镜像。"
    DO_PULL=false
else
    echo -e "✅ 已选择 ${GREEN}拉取最新镜像${NC} (架构: $PLATFORM)。"
    DO_PULL=true
fi
echo "----------------------------------------"

# ===========================
# 5. 执行验证与拉取
# ===========================
echo ""
FINAL_TARGET_IMAGES="" # 用于存放最终确实存在的镜像，防止 save 报错

for img in $TARGET_IMAGES; do
    img_label="$(image_display_label "$img")"
    if [ "$DO_PULL" = true ]; then
        if pull_image_for_platform "$img"; then
            FINAL_TARGET_IMAGES="$FINAL_TARGET_IMAGES $img"
        fi
    else
        # 检查本地是否存在该镜像，且架构必须与目标平台一致
        if docker image inspect "$img" > /dev/null 2>&1; then
            if image_matches_platform "$img"; then
                echo -e "${GREEN}✅ 本地已存在且架构匹配: $img_label ($(image_platform "$img"))${NC}"
                FINAL_TARGET_IMAGES="$FINAL_TARGET_IMAGES $img"
                continue
            fi

            echo -e "${YELLOW}⚠️ 本地镜像架构不匹配: $img_label，实际为 $(image_platform "$img")，期望 $PLATFORM${NC}"
            read -p "   👉 是否按目标架构重新拉取该镜像？ (y/n) [默认 y]: " PULL_WRONG_ARCH
            PULL_WRONG_ARCH=${PULL_WRONG_ARCH:-y}

            if [[ "$PULL_WRONG_ARCH" == "y" || "$PULL_WRONG_ARCH" == "Y" ]]; then
                if pull_image_for_platform "$img"; then
                    FINAL_TARGET_IMAGES="$FINAL_TARGET_IMAGES $img"
                fi
            else
                echo -e "⏩ 已跳过: $img_label"
            fi
        else
            echo -e "${YELLOW}⚠️ 本地未找到镜像: $img_label${NC}"
            read -p "   👉 是否现在拉取该镜像？ (y/n) [默认 y]: " PULL_MISSING
            PULL_MISSING=${PULL_MISSING:-y}

            if [[ "$PULL_MISSING" == "y" || "$PULL_MISSING" == "Y" ]]; then
                if pull_image_for_platform "$img"; then
                    FINAL_TARGET_IMAGES="$FINAL_TARGET_IMAGES $img"
                fi
            else
                echo -e "⏩ 已跳过: $img_label"
            fi
        fi
    fi
done

# ===========================
# 6. 打包镜像与导入提示
# ===========================
if [ -z "$FINAL_TARGET_IMAGES" ]; then
    echo -e "${RED}❌ 错误:经过筛选后,没有可用于打包的有效镜像,操作取消。${NC}"
    exit 1
fi

# 方式:分别导出到新建目录
OUTPUT_DIR="images_offline_${ARCH_NAME}_dir"
mkdir -p "$OUTPUT_DIR"
echo -e "📦 [正在分别打包] 将把以下镜像分别导出到目录 ${YELLOW}${OUTPUT_DIR}${NC} 中 ..."

    for img in $FINAL_TARGET_IMAGES; do
        if ! image_matches_platform "$img"; then
            echo -e "${RED}   ❌ 打包前架构校验失败: $img，实际为 $(image_platform "$img")，期望 $PLATFORM，已跳过${NC}"
            continue
        fi

        # 替换镜像名中的 / 和 : 为 _，确保它是合法的文件名
        SAFE_FILENAME=$(echo "$img" | tr '/:' '_').tar
        echo -e "   ⏳ 正在打包 ${GREEN}$(image_display_label "$img")${NC} -> ${OUTPUT_DIR}/${SAFE_FILENAME}"
        save_image_for_platform "$img" "${OUTPUT_DIR}/${SAFE_FILENAME}"
        if [ $? -ne 0 ]; then
            echo -e "${RED}   ❌ 打包失败: $img${NC}"
        fi
    done

echo -e "\n${GREEN}✅ 所有所选镜像已完成打包!${NC}"
ls -lh "$OUTPUT_DIR"

# 针对分目录导出方式的导入提示
echo -e "\n${CYAN}========================================${NC}"
echo -e "${CYAN}🚀 离线服务器导入指南:${NC}"
echo -e "1. 将目录 ${YELLOW}${OUTPUT_DIR}${NC} 整个上传至目标服务器"
echo -e "2. 在目标服务器上进入该目录并执行批量导入:"
echo -e "   ${GREEN}cd ${OUTPUT_DIR} && for f in *.tar; do docker load -i \"\$f\"; done${NC}"
echo -e "3. 镜像导入完成后,进入项目目录启动容器:"
echo -e "   ${GREEN}docker compose -f ${COMPOSE_FILE} up -d${NC}"
echo -e "${CYAN}========================================${NC}\n"
