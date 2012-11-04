bb
==

Command-line tool for submitting projects to blackboard and possibly doing other
things.

Requirements
------------

- bash
- curl
- sed
- openssl

Usage:
------

    $ bb <command> <args>

To test out that you can log in with bb, try this one:

    bb courses

Submitting an assignment
------------------------

    bb submit [<course> [<assignment>]] [-f <submission_file>]

The following describes how the tool should work, not how it works now.

The program reads your login info from `~/.netrc` or prompts you
to enter it.

    bb submit csc172 sorts -f my_sorts.zip
    Enter your net ID:
    > clehner
    Enter your password:
    >

Then bb logs in with the given credentials. If login succeeds, it tries to
find a course with a name similar to what you gave it in the `course` argument.

    Logged in as clehner.
    Found CSC172.2012FALL.77613 - THE SCI OF DATA STRUCTURES - 2012FALL

bb then examines the course's _Course Materials_ and looks for an assignment
whose title contains or is most similar to the string given in the `assignment`
argument.

    Found 2 assignments.
    1) Lab of 10/18: Sorts
    2) Lab of 10/23: More Sorts
    Choose an assignment:
    > 1

bb then submits the specified file or prompts you for a file to submit.

    Submitting my_sorts.zip...
    Submission accepted, 9:59 PM.

Todo
----

- Implement submit command
- Allow specifying text submission or submission comments?
- Testing
- Add other commands? (Declining balance?)
