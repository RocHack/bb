bb
==

**bb** is a command-line tool for interacting with a Blackboard Learn
installation. With bb you can submit labs and projects from the comfort of your
terminal. Currently it can be used with http://my.rochester.edu/ but it could
have support added for other schools' installations.

bb is not affiliated with or endorsed by Blackboard Inc. or the University of
Rochester. bb is made available under the terms of the MIT License.

Requirements
------------

Most Linux or Mac OS X machines have these.

- bash
- curl
- sed
- openssl or python

Usage
-----

    $ bb <command> <args>

To test out that you can log in with bb, try this one:

    bb courses

Submitting an assignment
------------------------

    bb submit [<course> [<assignment>]] [-f <submission_file>]

The following describes how the tool should work, not how it works now.

The program reads your login info from `~/.netrc` or prompts you
to enter it.

    $ bb submit csc172 sorts -f my_sorts.zip
    Enter your net ID:
    > clehner
    Enter your password:
    >

Then bb logs in with the given credentials. If login succeeds, it tries to
find a course with a name matching what you gave it in the `course` argument.

    Logged in as clehner.
    Found CSC172.2012FALL.77613 - THE SCI OF DATA STRUCTURES - 2012FALL

bb then examines the course's _Course Materials_ and looks for an assignment
whose title matches the string given in the `assignment` argument.

    Found 2 assignments.
    1) Lab of 10/18: Sorts
    2) Lab of 10/23: More Sorts
    Choose an assignment:
    > 1

bb then prompts you for a file if you didn't specify one in the arguments, and
then submits it.

    Submitting my_sorts.zip...
    Submission accepted, 9:59 PM.

Checking declining/Uros balance
------------------------------

    bb balance [-d] [-u]

This command prints your account balances. For example:

    $ bb balance
    516.42 26.03

To specify either declining or Uros, Use the option `-d` or `-u`, as follows:

    $ bb balance -d
    516.42

Todo
----

- Implement submit command
- Allow specifying text submission or submission comments?
- Testing
- Change verbosity: -v or -q options
- Add more commands?
