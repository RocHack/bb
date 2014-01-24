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

    bb submit [<course> [<assignment>]] [-f <submission_file>] [-t <submission_text>] [-c <comments>]

The program reads your login info from `~/.netrc` or prompts you
to enter it.

    $ bb submit csc172 sorts -f my_sorts.zip
    Net ID: clehner
    Password:

Then bb logs in with the given credentials. If login succeeds, it tries to
find a course with a name matching what you gave it in the `course` argument.

    Logged in as clehner.
    Found CSC172 2012FALL 77613 THE SCI OF DATA STRUCTURES

bb then examines the course's _Course Materials_ and looks for an assignment
whose title matches the string given in the `assignment` argument.

    Found 2 assignments.
    1) Lab of 10/18: Sorts
    2) Lab of 10/23: More Sorts
    Choose an assignment: 1

bb then prompts you for a file if you didn't specify a submission in the
arguments. Then the submission is sent.

    Submission accepted.

You can also specify a plain text submission with `-t`, and submission comments
with `-c`. The submission text can be instead of or in addition to a file. If
you do not specify a submission file but you specify a submission text, you will
not be prompted for the file.

Checking declining/Uros balance
------------------------------

    bb balance [-d] [-u] [-q]

This command prints your account balances. For example:

    $ bb balance
    516.42 26.03

To specify either declining or Uros, Use the option `-d` or `-u`:

    $ bb balance -d
    516.42

Looking up tuition information
------------------------------

Get your current tuition statement:

    $ bb bill

Get a PDF of your current statement:

    $ bb bill --pdf my-statement.pdf

Get your history of tuition payments (in CSV format):

    $ bb history

Verbose mode
----------

If bb has not been run recently, it will have to log in to Blackboard (and to
the Sequoia system for the balance command). This may take a while.

To get a better idea of what is happening, you can use the `-v` option to increase verbosity. This is useful for debugging bb. 

For example:

    $ bb balance -d -v
    Logged in.
    Logged in to Sequoia.
    516.42

To suppress the "Logged in..." messages, simply leave off the `-v` option.

Todo
----

- Allow specifying multiple files for submissions
- Add more commands
- Add help command for each command
- Allow fetching past tuition statements
