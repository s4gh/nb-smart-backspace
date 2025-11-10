# Overview
Netbeans IDE plugin with cutom "backspace" behavior:
* when you press "backspace" on empty line and cursor is on the "logical start of the line" - entire empty line is removed and cursor is put at the end of previous line
* when you press "backspace" on empty line and cursor is not on the "logical start of the line" - cursor is put to the logical start of the line if between "logical start of the line" and cursor you have only whitespaces
