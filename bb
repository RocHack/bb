#!/bin/bash
# bb - Command line utility for Blackboard®
# Copyright © Charles Lehner, 2012
# MIT License

# httpauth_url='https://my.rochester.edu/bbcswebdav/'
bb_server='my.rochester.edu'
bb_url="https://$bb_server"
login_path='/webapps/login/index'
frameset_path='/webapps/portal/frameset.jsp'
main_path='/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=_23_1'
upload_assignment_path='/webapps/blackboard/execute/uploadAssignment?action=submit'

sequoia_token_path='/webapps/bb-ecard-sso-bb_bb60/token.jsp'
sequoia_auth_url='https://ecard.sequoiars.com/eCardServices/AuthenticationHandler.ashx'
card_balance_url='https://ecard.sequoiars.com/eCardServices/eCardServices.svc/WebHttp/GetAccountHolderInformationForCurrentUser'

cookie_jar=~/.bbsession

# login info will be filled from ~/.netrc or by prompting the user
user_id=
password=
authenticated=

quiet_mode=

bb_request() {
	# Allow path or full url
	if [[ ${1:0:1} == "/" ]]; then
		url="$bb_url$1"
	else
		url="$1"
	fi
	shift
	check_cookies
	curl -s -b "$cookie_jar" -c "$cookie_jar" "$url" $@ 2>&-
	#echo curl -s -b $cookie_jar -c $cookie_jar $url >>bb.log
}

# check if a file is accessible to other users
insecure_file() {
	[[ -n `find $1 -perm +077` ]]
}

# get saved login info from ~/.netrc
parse_netrc() {
	netrc=~/.netrc
	[[ -e $netrc ]] || return 1

	# Warn the user if .netrc is accessible to other users
	if insecure_file $netrc
	then
		echo 'Warning: .netrc has insecure permissions.' >&2
	fi

	cred=`sed -n "/machine $bb_server/,/machine /p" $netrc`
	user_id=`sed -n 's/.*login \([^ ]*\).*/\1/p' <<< $cred`
	password=`sed -n 's/.*password \([^ ]*\).*/\1/p' <<< $cred`
}

# Check the cookie file and create it if necessary
check_cookies() {
	# check cookie file
	if [[ -e $cookie_jar ]]
	then
		if insecure_file $cookie_jar
		then
			echo "Warning: $cookie_jar has insecure permissions." >&2
		fi
	else
		# create the cookie jar
		touch $cookie_jar
		chmod 600 $cookie_jar
		false
	fi
}

# Check if the current session is valid
check_session() {
	[[ $authenticated ]] && return 0
	check_cookies || return 1

	# Check if a request results in a redirect to the login page.
	if [[ -z $(bb_request $frameset_path -i | \
		sed -n -e '/^Location: .*login/p' \
			-e '/location\.replace(/p') ]]
	then
		#echo Connected.
		authenticated=1
		return 0
	else
		authenticated=
		password=
		return 1
	fi
}

# Open a session with the blackboard server.
# This should be called before any logged-in blackboard interaction
authenticate() {
	# Check if the session cookies still work.
	if check_session; then
		[[ $quiet_mode ]] || echo Logged in.
		return 0
	fi

	# load credentials from file
	parse_netrc

	until [[ $authenticated ]]
	do
		login
	done
	[[ $quiet_mode ]] || echo Logged in as $user_id.
}

# base64 encode, needed for logging in
base64() {
	# try openssl or python
	openssl base64 2>&- || python -m base64 2>&- 
}

# Log in and validate the session.
login() {
	# prompt user if no credentials found
	if [[ -z $user_id ]]
	then
		read -p 'Net ID: ' user_id
	fi

	if [[ -z $password ]]
	then
		stty -echo
		read -p 'Password: ' password; echo
		stty echo
	fi

	# log in
	enc_pass=`echo -n $password | base64`
	if [[ -z `bb_request $login_path -d "user_id=$user_id&encoded_pw=$enc_pass&encoded_pw_unicode=." -b 'cookies_enabled=true'` ]]
	then
		authenticated=1
		return 0
	else
		authenticated=
		password=
		return 1
	fi
}

# main command

usage_main() {
	exec >/dev/stderr
	bb_help
	exit 64
}

bb_help() {
	echo 'Usage: bb <command> <args>'
	echo 'Commands:'
	echo '    submit     Submit an assignment for a course'
	echo '    courses    List your courses'
	echo '    balance    Get your declining/Uros balance'
	echo '    help       Get this help message'
	echo 'Global options:'
	echo '    -q         Suppress "Logged in..." messages'
}

invalid_command() {
	echo "bb: '$1' is not a bb command. See 'bb help'" >&2
	exit 127
}

# command: submit

usage_submit() {
	exec >&2
	echo 'Usage: bb submit [<course> [<assignment>]] [-t <submission_text>] [-f <submission_file>] [-c <comments>]'
	exit 64
}

# Get courses list from main page.
# Outputs courses by line in the form:
# path name term crn title
# If the course has no link, path is empty and the line will start with a space.
get_courses() {
	bb_request $main_path | sed -n '/course-record" valign="_top"/{ n;N;N;N; s/^\(.*tab_tab_group_id=_2_1&url=\/\([^"]*\)">\)*[^A-Z]*\([^<]*\) - [A-Z0-9]*[^A-Z0-9]*<.*"top">\([^<]*\)\.\([^<]*\)\.\([0-9]*\)<\/td>$/\/\2 \4 \5 \6 \3/p; }'
}

bb_courses() {
	authenticate
	get_courses | while read path name term crn title
	do
		# example:
		# path=/webapps/blackboard/execute/courseMain?course_id=_54745_1
		# name=CSC172
		# term=2012FALL
		# crn=77613
		# title='THE SCI OF DATA STRUCTURES'

		echo $name.$term.$crn - $title
	done
}

# Check that a file exists, is readable and nonempty
check_submission_file() {
	if [[ -z $1 ]]; then
		false
	elif [[ ! -e $1 ]]; then 
		echo "File '$1' does not exist." >&2
	elif [[ ! -f $1 ]]; then
		echo "'$1' is not a file." >&2
	elif [[ ! -r $1 ]]; then
		echo "File '$1' is not readable." >&2
	elif [[ $(du $1 | cut -f 1) -lt 1 ]]; then
		echo "File '$1' is empty." >&2
	else
		# File is okay
		return 0
	fi
	return 1
}

# Get a course matching a string,
# prompting the user to choose between multiple results.
# Writes the result to variable COURSE in the form "path name title"
get_course() {
	local search="$1"
	local num_matches=0
	local courses=
	local course=

	# Ignore courses with no path (can't submit items to those)
	local all_courses=`get_courses | sed '/^\/ /d'`

	# If the user specified no course, let them pick one now.
	if [[ -z $search ]]; then
		# List all courses
		courses="$all_courses"
	else
		# Find courses matching the search string
		courses=`grep -i "$search" <<< "$all_courses"`

		if [[ -z $courses ]]; then
			# If 0 courses match, let the user pick from all their courses.
			echo "Found no courses matching '$1'"
		else
			# If 1 course matches, use that one.
			num_matches=`wc -l <<< "$courses"` 
			if [[ $num_matches -eq 1 ]]; then
				course="$courses"
				# Trim off path
				echo Found ${course#* }.
			else
				# If >1 courses match, let the user pick from the matches.
				echo Found $num_matches courses.
			fi
		fi
	fi

	# Split strings at newline for select menu
	OIFS=$IFS
	IFS=$'\n'

	# Set the prompt string
	OPS3=$PS3
	PS3='Choose a course: '

	if [[ $num_matches -ne 1 ]]; then
		# Don't show the course page in the select menu
		select course in `sed 's/^[^ ]* //' <<<"$courses"`; do
			if [[ -n $course ]]; then
				# Put back the course path
				course=`grep -F "$course" <<<"$courses"`
				break
			fi
		done
	fi

	# Restore environment.
	IFS=$OIFS
	PS3=$OPS3

	# return result
	COURSE="$course"
}

# Get assignments from a Course Materials page or subpage.
get_assignments2() {
	local course_materials=`bb_request $1`

	# Follow links like "Laboratory Reports" and "Projects"
	echo "$course_materials" | sed -n 's/.*<a href="\(\/[^"]*listContent\.jsp?course_id=[0-9_]*[^"]*\)">.*/\1/p' | \
	while read assignment_path; do
		get_assignments2 $assignment_path
	done

	# Print the assignments on this page
	echo "$course_materials" | sed -n 's/.*<a href="\([a-z/]*uploadAssignment[^"]*\)"><[^>]*>\([^<]*\).*/\1 \2/p'
}

# List titles of all the assignments for a given course
# Format per line: "assignment_upload_path assignment_title"
get_assignments() {
	local course_path="$1"

	# Go to the Course Materials page
	local course_materials_path=`bb_request $course_path -L | \
		sed -n '/Course Material/ s/.*<a href="\([^"]*\)".*/\1/p'`

	get_assignments2 $course_materials_path
}

# Get an assignment for a given course path, prompting the user if necessary.
get_assignment() {
	local course_path="$1"
	local search="$2"
	local num_matches=0
	local all_assignments=`get_assignments $course_path`
	local assignments=
	local assignment=

	# If the user did not specify an assignment, let them choose now.
	if [[ -z $search ]]; then
		# List all assignments.
		assignments="$all_assignments"
	else
		# Find assignments matching the search string
		assignments=`grep -i "$search" <<< "$all_assignments"`

		if [[ -z $assignments ]]; then
			echo "Found no assignments matching '$2'"
			# If 0 assignments match, let the user pick from the whole list
			assignments="$all_assignments"
		else
			# If 1 assignment matches, use that one.
			num_matches=`wc -l <<< "$assignments"` 
			if [[ $num_matches -eq 1 ]]; then
				assignment="$assignments"
				# Trim off path
				echo Found ${assignment#* }.
			else
				# If >1 assignments match, let the user pick from the matches.
				echo Found $num_matches assignments.
			fi
		fi
	fi

	# Split strings at newline for select menu
	OIFS=$IFS
	IFS=$'\n'

	# Set the prompt string
	OPS3=$PS3
	PS3='Choose an assignment: '

	if [[ $num_matches -ne 1 ]]; then
		# Don't show the assignment path in the select menu
		select assignment in `sed 's/^[^ ]* //' <<<"$assignments"`; do
			if [[ -n $assignment ]]; then
				# Put back the assignment path
				assignment=`grep -F "$assignment" <<<"$assignments"`
				break
			fi
		done
	fi

	# Restore environment.
	IFS=$OIFS
	PS3=$OPS3

	# Return result.
	ASSIGNMENT="$assignment"
}

# upload_assignment form_path sub_text comments sub_file
upload_assignment() {
	local upload_form_path="$1"
	local submission_text="$2"
	local comments="$3"
	local submission_file="$4"

	# newFile_attachmentType=S for Smart Text, H for HTML, P for Plain Text

	filename=${submission_file##*/}

	if bb_request "$upload_form_path&action=submit" -f \
		-F "student_commentstext=$comments" \
		-F "studentSubmission.text=$submission_text" \
		-F "studentSubmission.type=P" \
		-F "newFile_attachmentType=L" \
		-F "newFile_fileId=new" \
		-F "newFile_LocalFile0=@$submission_file;filename=$filename" \
		-F "newFile_linkTitle=$filename"
	then
		echo Submission accepted.
	else
		echo Unable to submit.
		return 1
	fi
}

bb_submit() {
	local course=
	local assignment=
	local submission=
	local submission_text=
	local comments=
	local opt=

	# Process arguments
	for arg; do
		if [[ $opt == file ]]; then submission="$arg"; opt=
		elif [[ $opt == text ]]; then submission_text="$arg"; opt=
		elif [[ $opt == comments ]]; then comments="$arg"; opt=
		elif [[ $arg == '-f' ]]; then opt='file'
		elif [[ $arg == '-t' ]]; then opt='text'
		elif [[ $arg == '-c' ]]; then opt='comments'
		elif [[ $arg == '-q' ]]; then true
		elif [[ -z $course ]]; then course="$arg"
		elif [[ -z $assignment ]]; then assignment="$arg"
		else echo Unknown argument "$arg" >&2; exit 1
		fi
	done

	# If the user specified a submission file, check it
	if [[ -n $submission ]]; then
		check_submission_file $submission || exit 1
	fi

	# Establish session
	authenticate

	# Select the course interactively and get the course path
	get_course $course
	course_path=${COURSE%% *}

	# Select the assignment (interactive) and get the assignment upload path
	get_assignment $course_path $assignment
	assignment_upload_path=${ASSIGNMENT%% *}

	# Prompt for submission file if no file or text was specified.
	until [[ -n $submission_text ]] || check_submission_file $submission; do
		echo Enter the file name of your submission:
		read -e submission
	done

	upload_assignment "$assignment_upload_path" \
		"$submission_text" "$comments" \
		"$submission"
}

# command: balance

# Establish a session with Sequoia Retail Systems through Blackboard
authenticate_sequoia() {
	authenticate
	auth_token=`bb_request $sequoia_token_path | \
		sed -n 's/.*name="AUTHENTICATIONTOKEN" value="\([^"]*\).*/\1/p'`

	resp=`bb_request $sequoia_auth_url -F "AUTHENTICATIONTOKEN=$auth_token"`
	if [[ $resp == 'No destination url posted.' ]]; then
		[[ $quiet_mode ]] || echo Logged in to Sequoia.
		true
	else
		echo Unable to log in to Sequoia. >&2
		false
	fi
}

# Get account balances
bb_balance() {
	local print_declining=
	local print_uros=
	local print_both=
	local badarg=

	for arg; do
		case "$arg" in
			-d) print_declining=1;;
			-u) print_uros=1;;
			-q) ;;
			*) badarg=1;;
		esac
	done
	[[ $print_declining == $print_uros ]] && print_both=1

	if [[ $badarg ]]; then
		echo Usage: bb balance [-d] [-u] [-q] >&2
		return 1
	fi

	check_cookies

	# Try to re-use the session
	local balances
	balances=`bb_request $card_balance_url -d '{}' -f` || balances=
	if [[ -z $balances ]]; then
		authenticate_sequoia || return 1
		balances=`bb_request $card_balance_url -d '{}' -f`
	fi

	part1=${balances##*BalanceInDollars\":}
	part2=${balances#*BalanceInDollars\":}
	declining=${part1%%,*}
	uros=${part2%%,*}

	if [[ $print_both ]]; then echo $declining $uros
	elif [[ $print_declining ]]; then echo $declining
	elif [[ $print_uros ]]; then echo $uros
	fi
}

# command: help

usage_help() {
	bb_help
	exit 64
}

# main

if [[ $# -lt 1 ]]
then
	usage_main
fi

for arg; do
	if [[ $arg == '-q' ]]; then
		quiet_mode=1
	fi
done

cmd="$1"
shift
case "$cmd" in
	submit) bb_submit "$@";;
	courses) bb_courses $@;;
	balance) bb_balance $@;;
	help) bb_help $@;;
	*) invalid_command $cmd;;
esac
