#!/usr/bin/env bash
set -uo pipefail

VENV_DIR="${RKNN_VENV_DIR:-$HOME/venvs/rknn-toolkit2}"
FAILED=0

pass() {
    printf '[通过] %s\n' "$1"
}

fail() {
    printf '[失败] %s\n' "$1"
    FAILED=1
}

printf '%s\n' '========================================'
printf '%s\n' 'RKNN 模型转换环境检查'
printf '时间: %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')"
printf '主机: %s\n' "$(hostname)"
printf '%s\n' '========================================'

if [[ -f "$VENV_DIR/bin/activate" ]]; then
    # shellcheck disable=SC1090
    source "$VENV_DIR/bin/activate"
    pass "找到并激活虚拟环境: $VENV_DIR"
elif [[ -n "${VIRTUAL_ENV:-}" ]]; then
    pass "使用当前虚拟环境: $VIRTUAL_ENV"
else
    fail "未找到虚拟环境: $VENV_DIR"
    printf '%s\n' '如安装在其他位置，请这样运行：'
    printf '%s\n' 'RKNN_VENV_DIR=/你的/虚拟环境路径 ./check_rknn_environment.sh'
fi

if command -v python >/dev/null 2>&1; then
    pass "Python 可执行文件: $(command -v python)"
    python --version
else
    fail '未找到 python 命令'
fi

if command -v python >/dev/null 2>&1; then
    python - <<'PY'
import importlib
import importlib.metadata
import platform
import sys

packages = (
    ("numpy", "numpy"),
    ("onnx", "onnx"),
    ("onnxruntime", "onnxruntime"),
    ("opencv-python", "cv2"),
    ("torch", "torch"),
    ("rknn-toolkit2", "rknn.api"),
)

failed = False
print(f"[信息] Python: {sys.version.split()[0]}")
print(f"[信息] 系统: {platform.platform()}")

for distribution, module_name in packages:
    try:
        module = importlib.import_module(module_name)
        try:
            version = importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            version = getattr(module, "__version__", "版本未知")
        print(f"[通过] {distribution}: {version}")
    except Exception as error:
        failed = True
        print(f"[失败] {distribution}: {type(error).__name__}: {error}")

try:
    from rknn.api import RKNN
    instance = RKNN(verbose=False)
    instance.release()
    print("[通过] RKNN API 可以创建并释放转换器实例")
except Exception as error:
    failed = True
    print(f"[失败] RKNN API 初始化: {type(error).__name__}: {error}")

raise SystemExit(1 if failed else 0)
PY
    if [[ $? -ne 0 ]]; then
        FAILED=1
    fi
fi

printf '%s\n' '----------------------------------------'
if [[ "$FAILED" -eq 0 ]]; then
    printf '%s\n' '最终结果：环境检查通过，可以进行 RKNN 模型转换。'
else
    printf '%s\n' '最终结果：环境检查失败，请查看上面的 [失败] 项。'
fi
printf '%s\n' '========================================'

exit "$FAILED"
