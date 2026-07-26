-- Two statements in one file; this comment contains a semicolon; and must be stripped.
CREATE TABLE app_tags (id INT PRIMARY KEY, label VARCHAR(64));

# A hash comment, MySQL/MariaDB style.
/* A block comment;
   spanning lines. */
CREATE TABLE app_notes (id INT PRIMARY KEY, body VARCHAR(255));
