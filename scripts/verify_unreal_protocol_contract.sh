#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixtures="$repo_root/docs/contracts/fixtures"

ruby -rjson -e '
  allowed_delivery = %w[durable command ephemeral].freeze
  seen_message_ids = {}
  paths = ARGV.sort
  abort "no Unreal protocol fixtures" if paths.empty?
  paths.each do |path|
    root = JSON.parse(File.read(path))
    required = %w[protocol schemaVersion messageId type sentAt correlationId delivery payload]
    missing = required.reject { |field| root.key?(field) }
    abort "#{path}: missing #{missing.join(", ")}" unless missing.empty?
    abort "#{path}: wrong protocol" unless root["protocol"] == "gahyeon.unreal.v1"
    abort "#{path}: wrong schema version" unless root["schemaVersion"] == 1
    abort "#{path}: invalid delivery" unless allowed_delivery.include?(root["delivery"])
    if root["delivery"] == "durable"
      sequence = root["sequence"]
      abort "#{path}: durable sequence must be a non-negative integer" unless
        sequence.is_a?(Integer) && sequence >= 0
    else
      session_id = root["sessionId"]
      abort "#{path}: sessionId required for non-durable message" unless
        session_id.is_a?(String) && !session_id.strip.empty?
    end
    abort "#{path}: payload must be object" unless root["payload"].is_a?(Hash)
    if %w[generation.advanced interaction.generation.advanced].include?(root["type"])
      abort "#{path}: generation advance must be ephemeral" unless root["delivery"] == "ephemeral"
      generation = root["payload"]["generation"]
      reason = root["payload"]["reason"]
      allowed_reasons = %w[cognition_timeout client_reset stt_failed microphone_capture_aborted]
      abort "#{path}: invalid generation" unless generation.is_a?(Integer) && generation >= 0
      abort "#{path}: invalid generation reason" unless allowed_reasons.include?(reason)
    end
    message_id = root["messageId"]
    abort "#{path}: blank messageId" unless message_id.is_a?(String) && !message_id.strip.empty?
    abort "#{path}: duplicate messageId #{message_id}" if seen_message_ids[message_id]
    seen_message_ids[message_id] = path
  end
  puts "Validated #{paths.length} canonical Unreal protocol fixtures"
' "$fixtures"/*.json

# Keep all fixture classes visible to the strict Stage verifier. This script is
# intentionally engine-independent and safe to run before a licensed UE runner.
"$repo_root/scripts/verify_unreal_stage_scaffold.sh" >/dev/null
