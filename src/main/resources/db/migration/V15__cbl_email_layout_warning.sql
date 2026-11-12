-- A CBL PDF that was read and imported, but whose layout differs from the usual one (a heading is missing): kept on the e-mail record so a person
-- is told to check every line against the PDF, and so a run of such PDFs can raise the "CBL may have changed their format" alarm.
ALTER TABLE cbl_email_intake ADD COLUMN layout_warning VARCHAR(1000) NULL;
