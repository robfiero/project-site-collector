#!/usr/bin/env bash

INFO="\033[0;36m"
SUCCESS="\033[0;32m"
WARN="\033[0;33m"
ERROR="\033[0;31m"
RESET="\033[0m"

print_banner() {
  local title="$1"
  shift
  printf "\n"
  printf "%b==============================%b\n" "$INFO" "$RESET"
  printf "%b%s%b\n" "$INFO" "$title" "$RESET"
  printf "%b==============================%b\n" "$INFO" "$RESET"
  printf "\n"

  while [[ $# -gt 0 ]]; do
    printf "%-20s : %s\n" "$1" "$2"
    shift 2
  done

  printf "\n"
}

print_error() {
  printf "%bERROR:%b %s\n" "$ERROR" "$RESET" "$1" >&2
}

print_success() {
  printf "%bSUCCESS:%b %s\n" "$SUCCESS" "$RESET" "$1"
}
