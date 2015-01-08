#!/bin/bash
# bb - Command line utility for Blackboard®
# Copyright © Charles Lehner, 2012
# MIT License

# httpauth_url='https://my.rochester.edu/bbcswebdav/'
bb_server='my.rochester.edu'
bb_url="https://$bb_server"
login_path='/webapps/login/index'
frameset_path='/webapps/portal/frameset.jsp'
tab_action_path='/webapps/portal/execute/tabs/tabAction'
main_path='/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=_23_1'
upload_assignment_path='/webapps/blackboard/execute/uploadAssignment?action=submit'
course_grades_path='/webapps/bb-mygrades-bb_bb60/myGrades?stream_name=mygrades&course_id='
course_main_path='/webapps/blackboard/execute/courseMain?course_id='

sequoia_token_path='/webapps/bb-ecard-sso-bb_bb60/token.jsp'
sequoia_auth_url='https://ecard.sequoiars.com/eCardServices/AuthenticationHandler.ashx'
card_balance_url='https://ecard.sequoiars.com/eCardServices/eCardServices.svc/WebHttp/GetAccountHolderInformationForCurrentUser'

quikpay_token_path='/webapps/portal/quikpay.jsp'
quikpay_root='https://quikpayasp.com'
quikpay_auth_path='/rochester/tuition/payer.do'
quikpay_history_path='/rochester/qp/history/index.do'
quikpay_current_statement_path='/rochester/qp/ebill/currentStatementDispatcher.do'
quikpay_payment_path='/rochester/qp/epay/index.do'
quikpay_payment_confirm_path='/rochester/qp/epay/submitAmountMethod.do'
quikpay_payment_submit_path='/rochester/qp/epay/submitCheckECheckConfirmation.do'
quikpay_payment_process_path='/rochester/qp/epay/processPayment.do'
cookie_jar=~/.bbsession

# login info will be filled from ~/.netrc or by prompting the user
user_id=
password=
authenticated=

verbose_mode=
cookie_jar_checked=

# exit codes
EX_USAGE=64

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
}

# check if a file is accessible to other users
insecure_file() {
	[[ -n `find $1 -perm +066 2>&-; find $1 -perm /066 2>&-` ]]
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

# Check to see if curl is installed for current user
check_curl() {
	type curl >/dev/null 2>&1 || {
		echo "'curl' is not installed, install curl before running bb." >&2
		echo "info: http://curl.haxx.se/docs/manpage.html" >&2
		exit 1
	}
}

# Check the cookie file and create it if necessary
check_cookies() {
	# check cookie file
	if [[ -e $cookie_jar ]]
	then
		if [[ -z $cookie_jar_checked ]]
		then
			if insecure_file $cookie_jar
			then
				echo "Warning: $cookie_jar has insecure permissions." >&2
			fi
			cookie_jar_checked=1
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
		sed -n -e '/^Location: .*login/p') ]]
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
		[[ $verbose_mode ]] && echo Logged in.
		return 0
	fi

	# load credentials from file
	parse_netrc

	until [[ $authenticated ]]
	do
		login
	done
	[[ $verbose_mode ]] && echo Logged in as $user_id.
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
	exit 1
}

bb_help() {
	echo 'Usage: bb <command> <args>'
	echo 'Commands:'
	echo '    help       Get this help message'
	echo '    courses    List your courses'
	echo '    grades     Get grades for a course'
	echo '    materials  List or download course materials'
	echo '    submit     Submit an assignment for a course'
	echo '    balance    Get your declining/Uros balance'
	echo '    bill       Get your current tuition bill'
	echo '    payments   Get your history of tuition payments'
	echo '    pay        Make tuition payment'
	echo 'Global options:'
	echo '    -v         Increase verbosity'
}

invalid_command() {
	echo "bb: '$1' is not a bb command. See 'bb help'" >&2
	exit 127
}

# Pick an item out of a list.
# Usage: pick_item "$prompt" "$items" "$item_query"
# Finds a line in $items matching $item_query, or the user's input.
# Writes result to variable ITEM
#
pick_item() {
	local items=
	local prompt=${1:='Choose an item'}
	local all_items="$2"
	local search="$3"
	local num_matches=0

	# If the user specified no item, let them pick one now.
	if [[ -z $search ]]; then
		# List all items
		items="$all_items"
	else
		# Find items matching the search string
		items=$(grep -i "$search" <<< "$all_items")

		if [[ -z $items ]]; then
			# If 0 items match, let the user pick from all the items.
			echo "Found no items matching '$search'"
			items="$all_items"
		else
			# If 1 item matches, use that one.
			num_matches=$(sed -n '$=' <<< "$items")
			if [[ $num_matches -eq 1 ]]; then
				echo Found $items
				ITEM="$items"
				return
			else
				# If >1 items match, let the user pick from the matches.
				echo Found $num_matches items.
			fi
		fi
	fi

	# Split strings at newline for select menu
	OIFS=$IFS
	IFS=$'\n'

	# Set the prompt string
	OPS3=$PS3
	PS3="$prompt: "

	select item in $items; do
		ITEM="$item"
		[[ -n $item ]] && break
	done

	# Restore environment
	IFS=$OIFS
	PS3=$OPS3

	# Return status
	[[ -n "$item" ]]
}

# command: submit

usage_submit() {
	exec >&2
	echo 'Usage: bb submit [<course> [<assignment>]] [-t <submission_text>] [-f <submission_file>] [-c <comments>]'
	exit 1
}

bb_ajax_module() {
	mod_id="$1"
	bb_request $tab_action_path \
		-F 'action=refreshAjaxModule' \
		-F "modId=$mod_id" \
		-F 'tabId=_27_1' \
		-F 'tab_tab_group_id=_23_1'
}

# Get courses list from main page.
# Outputs courses by line in the form:
# path name term crn title
# If the course has no link, path is empty and the line will start with a space.
get_courses() {
	bb_ajax_module _452_1 | sed -n '/course-record" valign="_top"/{ n;N;N;N; s/^\(.*tab_tab_group_id=_2_1&url=\/[^"]*course_id=\([^"]*\)">\)*[^A-Z0-9]*\([^<]*\) - [A-Z0-9]*[^A-Z0-9]*<.*"top">\([^<]*\)\.\([^<]*\)\.\([0-9]*\)<\/td>$/\/\2 \4 \5 \6 \3/p; }'
}

bb_courses() {
	authenticate
	local current_term=
	local term=
	get_courses | while read cid name term crn title
	do
		# example:
		# path=/webapps/blackboard/execute/courseMain?course_id=_54745_1
		# name=CSC172
		# term=2012FALL
		# crn=77613
		# title='THE SCI OF DATA STRUCTURES'
		term="$(cut -c 5 <<< $term)$(cut -c 6- <<< $term | tr \
			'[:upper:]' '[:lower:]') $(cut -c -4 <<< $term)"
		if [[ "$term" != "$current_term" ]]; then
			echo "$term"
			current_term="$term"
		fi

		printf "	%5s %-8s -  %s\n" "$crn" "$(sed 's/^[A-Z]*/& /' <<< $name)" "$title"
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
		get_assignments2 $assignment_path $2
	done

	# If materials wanted, return them
	if [[ $2 == '-m' ]]; then
		[[ $verbose_mode ]] && echo "$course_materials" > rawMaterialsList.html
		echo "$course_materials" | \
		sed -n -e '
		# check for the color only documents seem to have
		/style="color:#[0-9a-fA-F]\{6\};"/{
			# check for an inline link, move on
			/a href/{
			s_.*<a href="\([^"]*\)".*<span[^>]*>\(.*\)</span>.*_\2\
\1_p
				b go
			}
			# find, print document name
			s_.*<span[^>]*>\(.*\)</span>.*_\1_p
			:mlloop
			# loop until document details are found (multiline)
			/.*<div[^>]*class="details".*/!{
				n
				b mlloop
			}
			# get the link out of the doc details
			/.*<div[^>]*class="details".*/{
				n
				s_.*<a[^>]*href="\([^"]*\)"[^>]*>.*_\1_p
			}
			# label if inline link found
			:go
		}'
		return 1
	fi

	# Print the assignments on this page
	echo "$course_materials" | sed -n 's/.*<a href="\([a-z/]*uploadAssignment[^"]*\)"><[^>]*>\([^<]*\).*/\1 \2/p'
}

# List titles of all the assignments for a given course
# Format per line: "assignment_upload_path assignment_title"
get_assignments() {
	local course_id="$1"
	local course_path="$course_main_path$course_id"

	# Go to the Course Materials page
	local course_materials_path=`bb_request $course_path -L | sed -n \
		'/<span title="Course Materials">/{
			s/.*<a href="\([^"]*\)".*/\1/p; q; }'`

	# $2 is a flag for returning course materials
	get_assignments2 $course_materials_path $2
}

# Get an assignment for a given course path, prompting the user if necessary.
get_assignment() {
	local course_id="$1"
	local search="$2"
	local num_matches=0
	local all_assignments=`get_assignments $course_id`
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

	[[ -n "$assignment" ]]
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
		elif [[ $arg == '-v' ]]; then true
		elif [[ $arg == '-h' ]]; then usage_submit
		elif [[ -z $course ]]; then course="$arg"
		elif [[ -z $assignment ]]; then assignment="$arg"
		else echo Unknown argument "$arg" >&2; exit $EX_USAGE
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
	course_id=${COURSE%% *}

	# Select the assignment (interactive) and get the assignment upload path
	if ! get_assignment $course_id $assignment; then
		echo >&2 'Unable to find assignment.'
		exit 1
	fi
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

# Establish a session with Sequoia Retail Systems through Blackboard
authenticate_sequoia() {
	authenticate
	auth_token=`bb_request $sequoia_token_path | \
		sed -n 's/.*name="AUTHENTICATIONTOKEN" value="\([^"]*\).*/\1/p'`

	resp=`bb_request $sequoia_auth_url -F "AUTHENTICATIONTOKEN=$auth_token"`
	if [[ $resp == 'No destination url posted.' ]]; then
		[[ $verbose_mode ]] && echo Logged in to Sequoia.
		true
	else
		echo Unable to log in to Sequoia. >&2
		false
	fi
}

# command: balance
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
			-v) ;;
			*) badarg=1;;
		esac
	done
	[[ $print_declining == $print_uros ]] && print_both=1

	if [[ $badarg ]]; then
		echo Usage: bb balance [-d] [-u] [-v] >&2
		return $EX_USAGE
	fi

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

	if [[ $print_both ]]; then
		printf "decl:\t\$ $declining\n"
		printf "uros:\t\$ $uros\n"
	elif [[ $print_declining ]]; then echo $declining
	elif [[ $print_uros ]]; then echo $uros
	fi
}

# Establish a session with QuikPAY (Tuition payment system) through Blackboard
authenticate_quikpay() {
	authenticate

	# Get the auth token/form
	auth_form=$(bb_request $quikpay_token_path | sed -n '
	/name=.*value=/{
		s/.*name="\([^"]*\)"[[:blank:]]*value="*\([^">]*\).*/\1=\2/
		s/ /+/g
		s/^/-d /
		p
	}')

	# Use the auth form to log in to quikpay
	auth_url=$quikpay_root$quikpay_auth_path
	if bb_request $auth_url $auth_form | grep -q 'Welcome to the'; then
		[[ $verbose_mode ]] && echo Logged in to Quikpay.
		true
	else
		echo Unable to log in to Quikpay. >&2
		false
	fi
}

# Make a request to Quikpay, authenticating if needed
quikpay_request() {
	local temp=`mktemp /tmp/bbout.XXXXXX`

	bb_request $quikpay_root$@ -i > $temp

	# Check for existing session
	if grep -q '500 Internal Server Error' $temp; then
		authenticate_quikpay
		bb_request $quikpay_root$@ > $temp
	fi

	cat $temp
	rm $temp
}

# command: payments
# Get tuition payment transaction history, in CSV format
bb_payments() {
	quikpay_request $quikpay_history_path | sed -n '
	/historyPaymentHistoryDetail/d
	/<t[dh]/{
		:a
		n
		/[^[:blank:]<]/!ba
		s/^[[:blank:]]*//
		s/<\/t[dh]>.*//
		H
	}
	/<\/tr>/{
		# output row
		g
		s/\n/,/g
		s/^,//
		s/<br>/ /g
		p
		# clear hold space
		s/.*//
		h
	}'
}

usage_pay() {
	echo "Usage: bb pay [-v] [-m <payment_method>] [<amount>]" >&2
	exit 1
}

parse_payment_profiles() {
	sed -n -e '/<select name="method"/{
	# get stored profiles
	/Or use a stored profile/!d
	s/.*<option[^<]*>Or use a stored profile[^<]*<\/option>//
	# begin repeat
	:a
	# done
	/<option/!q
	h
	# extract and print the first item
	s/<option[^>]* value="\([^"]*\)"[^>]*>[^(]*(\([^<]*\))[^<]*<\/option>.*/\1 (\2)/
	p
	g
	# remove the first item
	s/<option[^>]*>[^<]*<\/option>//
	# end repeat
	ba
	}'
}

parse_quikpay_error() {
	sed -n '
	/<h1 class="pageTitle">/{
		n
		s/'$'\r''//
		s/^[ 	]*//p
	}
	/etcErrorMessage/{
		:a
		N
		/<\/p>/!ba
		# extract error message
		y/\n'$'\r\t''/   /
		s/.*<p[^>]*> *//
		s/ *<\/p>.*//p
		q
	}'
}

# command: pay
# Make a tuition payment
bb_pay() {
	local opt=
	local amount=
	local method=
	local methods=
	local special=
	local temp=
	local confirm=
	local batch_id=
	local confirm_num=
	local result_msg=

	for arg; do
		if [[ $arg == '-v' ]]; then true
		elif [[ $arg == '-h' ]]; then usage_pay
		elif [[ $arg == '-m' ]]; then opt=method
		elif [[ $opt == method ]]; then method="$arg"; opt=
		elif [[ -z "$amount" ]]; then amount="$arg"
		else echo Unknown argument "$arg" >&2; exit $EX_USAGE
		fi
	done

	temp=`mktemp /tmp/bbout.XXXXXX`
	# get payment profiles and special form thing
	quikpay_request $quikpay_payment_path > "$temp"
	methods=$(parse_payment_profiles < "$temp")
	special=$(sed -n -e '/qp_epay_AmountMethodForm/ s/.*type="hidden" name="\([^"]*\)" value="\([^"]*\)".*/\1=\2/p' < "$temp")
	rm $temp

	if [[ -z "$methods" ]]; then
		cat >&2 <<ERR
You don't have any stored payment profiles.
Use the QuikPAY site to make your payment, and choose "Save Profile" to use the payment method with bb for subsequent payments.
ERR
		exit 1
	fi

	pick_item 'Choose a payment profile' "$methods" "$method" || exit
	method=${ITEM% (*}

	# prompt for payment amount if not given as argument
	while [[ -z "$amount" ]]
	do
		read -p 'Payment amount: ' amount || exit
	done

	# prepare for confirmation
	quikpay_request $quikpay_payment_confirm_path -L \
		-d "paymentAmount[0].amount=$amount"\
		-d "method=${method// /%20}"\
		-d "$special"\
		| sed -n '
	# begin with blank line
	1{
		s/.*//
		p
	}

	# error?
	/template_include_Content_error/{
		:a
		N
		/ <\/div>/!ba
		s/ *<[^>]*>//g
		s/'$'\r''//g
		s/\n//g
		s/&nbsp;/ /g
		p
		q
	}

	# get labels and values
	/<td class="summaryLabel"/blabel
	/<td class="ioLabel"/blabel
	/<td class="summaryValue"/bvalue
	/<td class="ioValue"/bvalue
	bz
	:label
		N
		/<\/td>/!blabel
		s/<[^>]*>//g
		y/\n/ /
		s/^['$'\r'' ]*//
		s/['$'\r'' ]*$//
		h
		bz
	:value
		N
		/<\/td>/!bvalue
		# add space
		# strip tags
		#/0311/l
		s/<[^>]*>//g
		s/'$'\r''//g
		y/\n/ /
		s/  */ /g
		s/  *$//
		s/^  *//
		# print in form "label: value"
		x
		G
		s/\n\n*/ /g
		p
		s/.*//
		h
	:z
	# print disclaimer
	/epay_ECheckConfirmation_eCheckDisclaimer/{
		N
		s/<[^>]*>/ /g
		s/  */ /g
		s/^ '$'\r''//
		s/\(\n\) /\1/
		s/['$'\r'' ]*$//
		G
		p
	}'

	until [[ $confirm ]]; do
		read -p 'Confirm this payment? [y/n] ' confirm
		case "$confirm" in
			n|N) echo 'Payment cancelled'; return 1;;
			y|Y) break;;
			*) echo -n; confirm=
		esac
	done

	quikpay_request $quikpay_payment_submit_path -LF "$special" > "$temp"

	batch_id=$(sed -ne 's/.* name="batchId" value="\([^"]*\)">.*/\1/p' "$temp")
	if [[ -z "$batch_id" ]]; then
		echo "Unable to find payment ID"
		parse_quikpay_error <"$temp"
		rm "$temp"
		return 1
	fi

	echo Processing payment ID $batch_id...
	quikpay_request $quikpay_payment_process_path -L \
		-F "$special"\
		-F "batchId=$batch_id" \
		-F "dummy=" > "$temp"
	confirm_num=$(sed <"$temp" -n '
	/epay_include_PaymentReceiptElement_confirmNumber/{
		:a
		N
		/<\/tr>/!ba
		# extract confirmation number
		s/.*<span class=attentionText>\([^<]*\)<\/span>.*/\1/p
		q
	}')
	result_msg=$(sed <"$temp" -n '
	/epay_include_PaymentReceiptElement_resultMessage/{
		:a
		N
		/<\/tr>/!ba
		s/[[:blank:]]*<[^>]*>[[:blank:]]*//g
		p
		q
	}')

	if [[ -z "$confirm_num" ]]; then
		echo "Payment failed."
		parse_quikpay_error <"$temp"
	else
		echo $result_msg
		echo "Confirmation number: $confirm_num"
	fi

	rm $temp
	[[ -n $confirm_num ]]
}


usage_bill() {
	echo Usage: bb bill [-v] [--pdf statement.pdf] >&2
	exit 1
}

# command: bill
# Look up a tuition statement
bb_bill() {
	local opt=
	local pdf_output_file=
	local pdf_path=

	for arg; do
		if [[ $arg == '-v' ]]; then true
		elif [[ $arg == '-h' ]]; then usage_bill
		elif [[ $arg == '--pdf' ]]; then opt=pdf; pdf_output_file=statement.pdf
		elif [[ $opt == pdf ]]; then pdf_output_file="$arg"; opt=
		else echo Unknown argument "$arg" >&2; exit $EX_USAGE
		fi
	done

	if [[ $pdf_output_file ]]; then
		# Get the PDF of the statement
		pdf_path=`quikpay_request $quikpay_current_statement_path -L |\
			sed -e '/submitStatementPDF/!d'\
				-e 's/^.*href="\([^"]*\)".*$/\1/; s/&amp;/\&/g'`

	else
		# Extract the statement text
		quikpay_request $quikpay_current_statement_path -L | sed -n '
			/ElementLabel/{
				n
				# replace annoying whitespace characters
				y/\n'$'\r\t''/   /
				# trim
				s/^ *//; s/ *$/:/
				# save for later
				h
			}
			/ElementValue/{
				n;N;N
				# clean up
				y/\n'$'\r\t''/   /
				s/ *<.*$//
				s/^ *//; s/ *$//
				# prepend label
				x;G
				s/\n/ /p
			}'
	fi

	if [[ $pdf_output_file ]]; then
		if [[ $pdf_path ]]; then
			echo Saving statement PDF to "$pdf_output_file"
			quikpay_request "$pdf_path" > "$pdf_output_file"
		else
			echo Failed to get PDF >&2
			exit 1
		fi
	fi
}

get_courses_all() {
	false
	#bb_request '/webapps/streamViewer/streamViewer'\
		#-d 'cmd=loadStream&streamName=mygrades_d&providers=%7B%7D&forOverview=false'
}

# command: grades
usage_grades() {
	exec >&2
	echo 'Usage: bb grades [<course>]'
	# Secret option: -c <course_id> instead of [<course>]
	exit 1
}

# utility for processing grades output
reverse_paragraphs() {
	sed ':a
	/^$/n
	/\n$/bb
	$bc
	N;ba
	:b
	G;h;d;:c
	s/$/\
/
	p
	g;s/\n*$//'
}

# shift headings down so that when the paragraphs are reversed, the headings
# preceed the paragraphs that they preceeded in the input
shift_headings() {
	sed '1,3 {
		/./!d
	}
	/^# /{
		s/^# //
		x
		/./p
		s/./-/g
	}
	${
		s/$/\
/
		p

		g
		p
		s/./-/g
	}'
}

# Look up your grades for a course
bb_grades() {
	local cid=
	local query=

	# Process arguments
	for arg; do
		if [[ $opt == cid ]]; then cid="$arg"; opt=
		elif [[ $arg == '-c' ]]; then opt='cid'
		elif [[ $arg == '-v' ]]; then true
		elif [[ $arg == '-h' ]]; then usage_grades
		elif [[ -z $query ]]; then query="$arg"
		else query="$query $arg"
		fi
	done

	if [[ -z $cid ]]; then
		# Get CID for course
		# TODO: allow querying past courses using get_courses_all
		get_course "$query"
		read cid name term crn title <<< "$COURSE"
		echo
	fi

	authenticate

	bb_request "$course_grades_path$cid" | sed -n -e '
		/<h3 class="section-title">/{
			s/.*<h3[^>]*>\([^<]*\).*/\
# \1/
			p
		}
		/<div class=.grade-item/{
			h
			s/.*//
			p

		}
		/<!-- Grade  -->/{
			:1
			N
			/<\/div>/!b1
			s/\n*[[:blank:]]*<[^>]*>\n*[[:blank:]]*//g
			s/.*/Grade: &/
			h
		}
		/<!-- Title -->/{
			:a
			N
			/<\/div>/!ba
			s/\n*[[:blank:]]*<[^>]*>\n*[[:blank:]]*//g
			p
			g
			/<div/!p
		}
		/<div class="info">/{
			:b
			N
			/<\/div>/!bb
			s/\n*[[:blank:]]*<[^>]*>\n*[[:blank:]]*//g
			/./p
		}
		/<!-- GRADE [^ ]* -->/{
			:c
			N
			/grade-label/!bc
			s/\n*[[:blank:]]*\(.*\)<span class=.grade-label.>\([^<]*\).*/\2: \1/g
			s/<[^>]*>\(\n*[[:blank:]]*\)*//g
			p
		}

		/studentGradesCommentPreview/ {
			# get content string
			:d
			N
			/<\/div>/!bd
			# save content for later
			h
			n

			# get type (e.g. Description)
			:e
			/value=".*./bf
			N
			be
			:f

			# skip comment type
			/Comments/d;

			# get the type and put a colon after it
			s/[[:blank:]]*<input[^>]* value=.\([^\"]*\).[^>]*>/\1: /;
			# paste the content after the type and colon
			G

			# if there is an extra description, parse it
			/<span class=.extra-description./{
				# save extra desc for later. strip it out
				h
				s/<span class=.extra-description.>[^<]*//
				# remove tags and spaces but leave space after colon
				s/\n*[[:blank:]]*<[^>]*>\n*[[:blank:]]*//g
				# print if it has content
				/: ./p
				# bring back the extra description and print it
				g
				s/.*<span class=.extra-description.>\([^<]*\).*/\1/
			}

			# strip tags and whitespace
			s/\n*[[:blank:]]*<[^>]*>//g
			# print what we have if it exists
			/./p
		}
	' | shift_headings | reverse_paragraphs
	echo
}

usage_materials() {
	echo Usage: bb materials [-v] [\<course\>] [\<document\>] >&2
	exit 1
}

# Look up or download course materials
get_materials() {
	local query=
	local course=

	# Process arguments
	for arg; do
		if [[ $arg == '-v' ]]; then true
		elif [[ $arg == '-h' ]]; then usage_materials
		elif [[ -z $course ]]; then course="$arg"
		elif [[ -z $query ]]; then query="$arg"
		else query="$query $arg"
		fi
	done

	# Establish session
	authenticate

	# Select the course interactively and get the course path
	get_course $course
	course_id=${COURSE%% *}

	# Get all the materials for the given course
	all_materials=`get_assignments $course_id -m | sed -n\
		-e '/span/!{ s/\(.*\)/	\1/; p; }'\
		-e '/span/{ s/\(.*\)<\/span>/\1 [dir]/; p; }'`
	[[ $verbose_mode ]] && echo "$all_materials" > allRoutes.txt

	# With materials, determine target document (remove routes)
	materials=`echo "$all_materials" | sed -e '/^	\//{ d; }'`

	# Routes are anything with a first dash, exclude
	materials=`grep -i "$query" <<< "$materials"`
	num_matches=`echo "$materials" | wc -l | sed -e 's/^[ \t]*//'`

	# Check if any materials found
	if [[ $num_matches -gt 1 ]]; then
		echo "Found $num_matches documents."
	elif [[ $materials != "*[dir]*" ]]; then
		echo "No materials found for current course."
		return 1
	fi

	# Split strings at newline for select menu
	OIFS=$IFS
	IFS=$'\n'

	# Set the prompt string - copied from courses way
	OPS3=$PS3
	PS3='Choose a material to download: '

	# Prompt user to choose an item
	if [[ $num_matches -gt 1 ]]; then
		select material in $materials; do
			break
		done
	# If just one matching item, autoselect
	else
		material="$materials"
	fi

	# Restore environment.
	IFS=$OIFS
	PS3=$OPS3

	# Figure out the location of doc
	url=`echo "$all_materials" | grep -A 1 "$material" | sed -ne '2 s/^	//p'`
	material=`echo "$material" | sed -e 's/^[ \t]*//'`

	# TODO: Option to just dump everything, or all contents in a dir?

	# Return desired materials - s for starting link
	echo Target material: $material
	s_url="$bb_url$url"

	# Get the actual path of the document (for naming)
	# t for temp link, r for redirected link
	r_url=`bb_request -i "$s_url" 2>&1 | sed -ne "s/.*\(content-rid.*\)/\1/p"`
	t_url=`echo "$s_url" | sed -ne "s/^\(.*\)content-rid.*/\1/p"`

	# Get what the file type is from its name (dirty)
	full_url="$t_url$r_url" # resolved, full path
	[[ $verbose_mode ]] && echo Found material @ $full_url
	filename=$(bb_request -OJw '%{filename_effective}' "$full_url")

	# Write the desired file to local file system
	filename=`echo "$filename" | sed -e 's/%20/ /g' -e 's/$//'`
	bb_request -L -# "$s_url" > "$filename"
	echo Downloaded $filename
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

# check if user has curl

check_curl

for arg; do
	if [[ $arg == '-v' ]]; then
		verbose_mode=1
	fi
done

cmd="$1"
shift
case "$cmd" in
	help|-h) bb_help $@;;
	submit) bb_submit "$@";;
	courses) bb_courses $@;;
	balance) bb_balance $@;;
	bill) bb_bill $@;;
	payments) bb_payments $@;;
	pay) bb_pay $@;;
	grades) bb_grades "$@";;
	materials) get_materials "$@";;
	*) invalid_command $cmd;;
esac
