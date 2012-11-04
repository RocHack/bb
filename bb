#!/bin/bash
# bb - Command line utility for Blackboard®
# Copyright © Charles Lehner, 2012
# MIT License

# httpauth_url='https://my.rochester.edu/bbcswebdav/'
bb_server='my.rochester.edu'
bb_url="https://$bb_server/"
login_path='webapps/login/'
frameset_path='webapps/portal/frameset.jsp'
main_path='webapps/portal/execute/tabs/tabAction?tab_tab_group_id=_23_1'

cookie_jar=~/.bbsession

# login info will be filled from ~/.netrc or by prompting the user
user_id=''
password=''
authenticated=

bb_request() {
	#echo curl -s -b $cookie_jar -c $cookie_jar $bb_url$@ >>bb.log
	curl -s -b $cookie_jar -c $cookie_jar $bb_url$@ 2>&-
}

# check if a file is accessible to other users
insecure_file() {
	perms=$(stat $1 --format %a)
	test ${perms:1} -ne '00'
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

	cred=$(sed -n "/machine $bb_server/,/machine /p" $netrc)
	user_id=$(sed -n 's/.*login \([^ ]*\).*/\1/p' <<< $cred)
	password=$(sed -n 's/.*password \([^ ]*\).*/\1/p' <<< $cred)
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
		return 1
	fi
}

# check if the current session is valid
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
		return 1
	fi
}

# Open a session with the blackboard server.
# This should be called before any logged-in blackboard interaction
authenticate() {
	check_session && return 0

	# load credentials from file
	parse_netrc

	until [[ $authenticated ]]
	do
		login
	done
}

# Log in and validate the session.
login() {
	# prompt user if no credentials found
	if [[ -z $user_id ]]
	then
		echo Enter your Net ID:
		read user_id
	fi

	if [[ -z $password ]]
	then
		echo Enter your password:
		stty -echo
		read password
		stty echo
	fi

	# log in
	enc_pass=$(echo -n $password | openssl base64)
	#echo Logging in...
	bb_request $login_path -d "user_id=$user_id&encoded_pw=$enc_pass" >&-
	check_session && echo Logged in as $user_id.
}

usage_main() {
	exec >/dev/stderr
	bb_help
	exit 64
}

bb_help() {
	echo 'Usage: bb <command> <args>'
	echo 'Commands:'
	echo '    submit     Submit an assignment for a course'
	echo '    courses    List your courses.'
	echo '    help       Get this help message.'
}

invalid_command() {
	exec >&2
	echo "bb: $1 is not a bb command. See 'bb help'"
	exit 127
}

# command: submit

usage_submit() {
	exec >&2
	echo 'Usage: bb submit [<course> [<assignment>]] [-f <submission_file>]'
	exit 64
}

# Get courses list from main page
get_courses() {
	bb_request $main_path | sed -n '/course-record" valign="_top"/{n;N;N;N;s/^\(.*tab_tab_group_id=_2_1&url=\/\([^"]*\)">\)\?\s*\([^<]*[a-zA-Z0-9;,.]\)\s*<.*"top">\([^<]*\)<\/td>$/\/\2 \4 \3/p}'
}

bb_courses() {
	authenticate
	get_courses | while read course_path course_name course_title 
	do
		# example:
		# course_path=/webapps/blackboard/execute/courseMain?course_id=_54745_1
		# course_name='CSC172.2012FALL.77613'
		# course_title='THE SCI OF DATA STRUCTURES - 2012FALL'

		echo $course_name - $course_title
	done
}

bb_submit() {
	[[ $# -lt 4 ]] && usage_submit
	course="$2"
	assignment="$3"
	submission="$4"

	echo Not implemented. >&2
	exit 1

	authenticate

	# Get the course list
	get_courses | while read course_path course_name course_title 
	do
		echo $course_title
		# Make sure course_path is not just '/'
	done

	# If the user specified a submission file, make sure it is nonempty.

	# If the user specified no course, let them pick one now.

	# Otherwise find a course matching the command line argument.
		# If >1 course matches, let the user pick one from the matches.
		# If 0 courses match, let the user pick from all their courses.

	# Proceed in a similar fashion for choosing the assignment.
	# Look for assignments in all the Course Materials pages.

	# If the user specified no submission file, prompt for it now.
	# Check that the submission file exists and is nonempty.

	# Embed the submission file in the form data and submit it.
	# Check for a positive result.
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

case "$1" in
	submit) bb_submit $@;;
	courses) bb_courses;;
	help) bb_help $@;;
	*) invalid_command $@;;
esac
