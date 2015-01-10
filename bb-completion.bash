#!/bin/bash

# Prints "$1" if "$1" is not in the current arguments
_bb_arg() {
    local is_seen=false
    for word in "${COMP_WORDS[@]}"; do
        if [[ "$word" = "$1" ]]; then
            is_seen=true
            break
        fi
    done
    $is_seen || printf -- "$1"
}

# Automatically adds -v and -h to the option list if they haven't already been
# given
_bb_set_options() {
    COMPREPLY=( $(compgen -W "$1 $(_bb_arg "-v") $(_bb_arg "-h")" -- \
        "${COMP_WORDS[$COMP_CWORD]}") )
}

_bb_use_files() {
    COMPREPLY=( $(compgen -f "${COMP_WORDS[$COMP_CWORD]}") )
}

_bb_no_options() {
    COMPREPLY=()
}

_bb_balance_complete() {
    _bb_set_options "$(_bb_arg "-u") $(_bb_arg "-d") $(_bb_arg "-h")"
}

_bb_bill_complete() {
    case "${COMP_WORDS[$COMP_CWORD-1]}" in
        --pdf)
            _bb_use_files
            ;;
        *)
            _bb_set_options "$(_bb_arg "--pdf") $(_bb_arg "-h")"
    esac
}

_bb_courses_complete() {
    _bb_no_options
}

_bb_grades_complete() {
    _bb_set_options "$(_bb_arg "-h")"
}

_bb_materials_complete() {
    _bb_set_options "$(_bb_arg "-h")"
}

_bb_submit_complete() {
    case "${COMP_WORDS[$COMP_CWORD-1]}" in
        -f)
            _bb_use_files
            ;;
        -t|-c)
            _bb_no_options
            ;;
        *)
            _bb_set_options "$(_bb_arg "-f") $(_bb_arg "-t") $(_bb_arg "-c")"
    esac
}

_bb_pay_complete() {
    _bb_set_options "$(_bb_arg "-m")"
}

_bb_payments_complete() {
    _bb_no_options
}

_bb_help_complete() {
    _bb_no_options
}

_bb_cmd() {
    printf -- "${COMP_WORDS[1]}"
}

_bb_complete() {
    local commands="balance bill courses grades materials submit pay payments help -h"

    if [[ $COMP_CWORD -le 1 ]]; then
        _bb_set_options "$commands"
        return $?
    fi

    local cmd="$(_bb_cmd)"

    [[ "$cmd" != "-h" ]] || cmd=help

    if ! hash _bb_${cmd}_complete 2>/dev/null; then
        _bb_set_options ""
        return $?
    fi

    _bb_${cmd}_complete
}

complete -F _bb_complete bb
