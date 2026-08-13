#!/usr/bin/env bash
set -euo pipefail

container_name="gahyeon-piper-recording"
image="rhasspy/piper-recording-studio:latest"
port="${PIPER_RECORDING_PORT:-8765}"
output_dir="${PIPER_RECORDING_OUTPUT:-$(pwd)/output/piper-recording-studio}"
action="${1:-status}"

case "$action" in
  start)
    mkdir -p "$output_dir"
    if docker container inspect "$container_name" >/dev/null 2>&1; then
      docker start "$container_name" >/dev/null
    else
      docker run -d \
        --platform linux/amd64 \
        --name "$container_name" \
        --restart unless-stopped \
        -p "127.0.0.1:${port}:8000" \
        -v "${output_dir}:/app/output" \
        "$image" >/dev/null
    fi
    echo "Piper Recording Studio: http://127.0.0.1:${port}"
    echo "Recordings: ${output_dir}"
    ;;
  stop)
    docker stop "$container_name" >/dev/null
    echo "Stopped ${container_name}"
    ;;
  status)
    docker ps -a \
      --filter "name=^/${container_name}$" \
      --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    ;;
  *)
    echo "Usage: $0 {start|stop|status}" >&2
    exit 2
    ;;
esac
