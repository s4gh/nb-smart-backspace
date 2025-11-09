# Overview
Netbeans IDE plugin with cutom "backspace" behavior:
* when you press "backspace" on empty line and cursor is on the "logical start of the line" - entire empty line is removed and cursor is put at the end of previous line
* when you press "backspace" on empty line and cursor is not on the "logical start of the line" - cursor is put to the logical start of the line if between "logical start of the line" and cursor you have only whitespaces

So far "logical start of the line" is determined by the start of the previous line. This simply approach but it does not work in some cases - e.g. when it is first line in some logical block (e.g. "if" block). 
In such casese first "backspace" willo put cursor at the place where "if" block starts. Next "backspace" will remove empty line.
While this behavior in such cases is not ideal as for me it is still more convenient compared to default implementation.

