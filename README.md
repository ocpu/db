A super charged database connection with a active record and awesome functions.

- [General idea](#General-idea)
- [Database](#Database)
- [Active Record](#Active-Record)
- [Features](#Features)
- [Example](#Example)
  - [Generated tables in SQLite](#Generated-tables-in-SQLite)
  - [Generated tables in MySQL](#Generated-tables-in-MySQL)

## General idea

I created this mainly a side project to make a interface with a database where there is no user created sql. If there was user created sql it would be easily executed.

As such there are 2 classes. One holds the simplified database connection and the other being the active record class.

## Database

The database class holds a connection to any database type. Creating a connection is as easy as calling one of the constructors.

1. `io.opencubes.sql.Databse(dsn string)`
2. `io.opencubes.sql.Databse(dsn string, user string, password string)`

The some DSN examples can be `sqlite::memory:`, `sqlite:./db.sqlite`, `mysql://localhost/test`.

Since this class only simplifies the general connection functions it does not implement any connectors/drivers for the different database types. But here is a not so complete list of the existing ones (Gradle):

- MySQL
  - `compile 'mysql:mysql-connector-java:8.0.11'`
  - `compile group: 'mysql", name: 'mysql-connector-java', version: '8.0.11'`
- SQLite
  - `compile 'org.xerial:sqlite-jdbc:3.25.2"`
  - `compile group: "org.xerial", name: 'sqlite-jdbc', version: '3.25.2'`
- SQL Server
  - `compile 'com.microsoft.sqlserver:mssql-jdbc:7.2.2.jre8'`
  - `compile group: 'com.microsoft.sqlserver', name: 'mssql-jdbc', version: '7.2.2.jre8'`

## Active Record

The active record class is derived from the [Active Record Pattern][wiki-ar]. This implementation has a few extra features like generating ddl from how the class is structured with help of some delegates. You can sync the implementation you write in Kotlin with the one in the database. Reference other tables with a property. Have a property referene a list of objects.

Enum values are supported as a value type and do not worry about the SQLite not supporting it, it is converted to a int representing the enum value.

## Features

| Feature                               | Status |
| :------------------------------------ | :----: |
| Simple SQL execution                  |   ✓    |
| Result Set Wrapper                    |   ✓    |
| Active Record                         |   ✓    |
| Many To Many Field                    |   ✓    |
| One To Many Field                     |   ✓    |
| Automatic Table Names                 |   ✓    |
| Table Creation SQL                    |   ✓    |
| Find Single Object                    |   ✓    |
| Find Multiple Objects                 |   ✓    |
| Search Single Object                  |   ✓    |
| Search Multiple Objects               |   ✓    |
| Get All Objects                       |   ✓    |
| Table Migration SQL                   |   ✓    |
| Custom Select Query for Active Record |   ✕    |

## Example

The following example demonstrates how to connect to a sqlite in memory database and how you can create some tables resembling something like twitter.

```kotlin
import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Database

fun generateSalt(): String = "(SALT)"

class User() : ActiveRecord() {
  constructor(handle: String) : this() {
    this::handle.set(handle)
  }

  // Instance properties or row columns
  val id by value<Int>()
  val handle by value<String>()
  var name by value<String?>()
  var email by value<String?>()
  var bio by value<String?>()
  private var password by value<String>()
  private val salt by value(::generateSalt)

  override val metadata = Metadata {
    autoIncrement(User::id)
    index(User::handle)
    unique {
      +User::handle
      group("password", User::password, User::salt)
    }

    // NOT needed but gives you more control of the SQL types
    User::handle use type (Type.VARCHAR, 32)
    User::name use type (Type.VARCHAR, 64)
    User::email use type (Type.VARCHAR, 128)
    User::bio use type (Type.VARCHAR, 180)
    User::password use type (Type.VARCHAR, 64)
    User::salt use type (Type.VARCHAR, 64)
  }

  // Properties describing complex relationships
  val follows by referenceMany<User>()
  val followers by referenceMany(reverse = User::follows)
  val posts by referenceMany(Post::author)
  val followingPosts by receiveMany(from = User::follows, mapper = Post::author)
}

class Post : ActiveRecord() {
  // Instance properties or row columns
  val id by value<Int>()
  val content by value<String>()
  val author by reference<User>()
  val parent by reference<Post?>()
  val created by value(Database.Current::timestamp)

  override val metadata = Metadata {
    autoIncrement(Post::id)

    // NOT needed but gives you more control of the SQL types
    Post::content use type (Type.VARCHAR, 180)
  }

  // Properties describing complex relationships
  val likes by referenceMany<User>()
  val tags by referenceMany<Tag>()
}

class Tag : ActiveRecord() {
  // Instance properties or row columns
  val id by value<Int>()
  val content by value<String>()

  override val metadata = Metadata {
    autoIncrement(Tag::id)
    index(Tag::content)
    unique(Tag::content)

    // NOT needed but gives you more control of the SQL types
    Tag::content use type (Type.VARCHAR, 32)
  }

  // Properties describing complex relationships
  val posts by referenceMany<Post>()
}

fun main() {
  // Make a SQLite in memory database and set it as the globally accessible one.
  Database("sqlite::memory:").asGlobal

  // Create the tables, connection tables and calculates any differences in the
  // database and if possible corrects them.
  ActiveRecord.migrate(User::class, Post::class, Tag::class)
}
```

### Generated tables in SQLite

```SQL
CREATE TABLE users (
  id INTEGER NOT NULL PRIMARY KEY DEFAULT rowid,
  bio TEXT NULL,
  email TEXT NULL,
  handle TEXT NOT NULL,
  name TEXT NULL,
  password TEXT NOT NULL,
  salt TEXT NOT NULL,

  CONSTRAINT users_ux_handle UNIQUE (handle),
  CONSTRAINT users_ux_password UNIQUE (password, salt)
);

CREATE TABLE posts (
  id INTEGER NOT NULL PRIMARY KEY DEFAULT rowid,
  author_id INTEGER NOT NULL,
  parent_id INTEGER,
  content TEXT NOT NULL,
  created TIMESTAMP NOT NULL,

  CONSTRAINT posts_fk_author_id FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE NO ACTION,
  CONSTRAINT posts_fk_parent_id FOREIGN KEY (parent_id) REFERENCES posts (id) ON DELETE NO ACTION
);

CREATE TABLE tags (
  id INTEGER NOT NULL PRIMARY KEY DEFAULT rowid,
  content TEXT NOT NULL,

  CONSTRAINT tags_ux_content UNIQUE (content)
);

CREATE TABLE IF NOT EXISTS follows (
  follows_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,

  CONSTRAINT follows_fk_follows_id FOREIGN KEY (follows_id) REFERENCES users (id),
  CONSTRAINT follows_fk_user_id FOREIGN KEY (user_id) REFERENCES users (id)
); -- Link table

CREATE TABLE IF NOT EXISTS post_tags (
  post_id INTEGER NOT NULL,
  tag_id INTEGER NOT NULL,

  CONSTRAINT post_tags_fk_post_id FOREIGN KEY (post_id) REFERENCES tags (id),
  CONSTRAINT post_tags_fk_tag_id FOREIGN KEY (tag_id) REFERENCES posts (id)
); -- Link table

CREATE TABLE IF NOT EXISTS upvotes (
  post_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,

  CONSTRAINT upvotes_fk_post_id FOREIGN KEY (post_id) REFERENCES users (id),
  CONSTRAINT upvotes_fk_user_id FOREIGN KEY (user_id) REFERENCES posts (id)
); -- Link table
```

### Generated tables in MySQL

```SQL
CREATE TABLE users (
  id INTEGER NOT NULL PRIMARY KEY AUTO_INCREMENT,
  bio VARCHAR(180) NULL,
  email VARCHAR(128) NULL,
  handle VARCHAR(32) NOT NULL,
  name VARCHAR(64) NULL,
  password VARCHAR(64) NOT NULL,
  salt VARCHAR(64) NOT NULL,

  INDEX users_ix_handle (handle),
  INDEX users_ix_id (id),

  CONSTRAINT users_ux_handle UNIQUE (handle),
  CONSTRAINT users_ux_password UNIQUE (password, salt)
);

CREATE TABLE posts (
  id INTEGER NOT NULL PRIMARY KEY AUTO_INCREMENT,
  author_id INTEGER NOT NULL,
  parent_id INTEGER,
  content VARCHAR(180) NOT NULL,
  created TIMESTAMP NOT NULL,

  INDEX posts_ix_author_id (author_id),
  INDEX posts_ix_parent_id (parent_id),
  INDEX posts_ix_id (id),

  CONSTRAINT posts_fk_author_id FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE NO ACTION,
  CONSTRAINT posts_fk_parent_id FOREIGN KEY (parent_id) REFERENCES posts (id) ON DELETE NO ACTION
);

CREATE TABLE tags (
  id INTEGER NOT NULL PRIMARY KEY AUTO_INCREMENT,
  content VARCHAR(32) NOT NULL,

  INDEX tags_ix_content (content),
  INDEX tags_ix_id (id),

  CONSTRAINT tags_ux_content UNIQUE (content)
);

CREATE TABLE IF NOT EXISTS follows (
  follows_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,

  INDEX follows_ix_follows_id (follows_id),
  INDEX follows_ix_user_id (user_id),

  CONSTRAINT follows_fk_follows_id FOREIGN KEY (follows_id) REFERENCES users (id),
  CONSTRAINT follows_fk_user_id FOREIGN KEY (user_id) REFERENCES users (id)
); -- Link table

CREATE TABLE IF NOT EXISTS post_tags (
  post_id INTEGER NOT NULL,
  tag_id INTEGER NOT NULL,

  INDEX post_tags_ix_post_id (post_id),
  INDEX post_tags_ix_tag_id (tag_id),

  CONSTRAINT post_tags_fk_post_id FOREIGN KEY (post_id) REFERENCES tags (id),
  CONSTRAINT post_tags_fk_tag_id FOREIGN KEY (tag_id) REFERENCES posts (id)
); -- Link table

CREATE TABLE IF NOT EXISTS upvotes (
  post_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,

  INDEX upvotes_ix_post_id (post_id),
  INDEX upvotes_ix_user_id (user_id),

  CONSTRAINT upvotes_fk_post_id FOREIGN KEY (post_id) REFERENCES users (id),
  CONSTRAINT upvotes_fk_user_id FOREIGN KEY (user_id) REFERENCES posts (id)
); -- Link table
```

[wiki-ar]: https://en.wikipedia.org/wiki/Active_record_pattern
