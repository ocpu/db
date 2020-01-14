- [What is this?](#what-is-this)
- [Models](#models)
- [Model Drivers](#model-drivers)
- [Example](#example)
  - [Generated tables in SQLite](#generated-tables-in-sqlite)
  - [Generated tables in MySQL](#generated-tables-in-mysql)

## What is this?

This is a database Object Relational Model (ORM) library for Kotlin. This ORM heavily uses delegates to provide you a simple way to declare, synchronize, and use tables and their column/property values. The models you write can be used for more than one database and no database specific code will ever be written by you.

This ORM library tries as much as possible to create database provider native solutions for all code that is generated in the background for your models.

## Models

As briefly covered above the [Active Record pattern][wiki-ar] is used to provide models as classes, and have delegated properties describe columns and relations.

Column values can be a any single value in Kotlin. The value will be serialized into the database appropriately. A short list of types are strings, numbers, timestamps, enums, etc. Relationships can be described in many to many, one to many, and one to one. Please refer to the [example](#example) below.

## Model Drivers

The model drivers are links between the database and a general interface that does not use sql strings as a primary communication tool. As such they provide a builder for when you want to select things from the database.

Model Drivers also services like generation of data definitions, and eventual migration definitions that apply new changes to the database table based on the model class.

This ORM library does not provide connection drivers that creates and upholds a connection with the database. As such you have to import them yourself. Here is a little list of some libraries that provide that.

- MySQL
  - `compile 'mysql:mysql-connector-java:8.0.11'`
- SQLite
  - `compile 'org.xerial:sqlite-jdbc:3.25.2'`
- SQL Server
  - `compile 'com.microsoft.sqlserver:mssql-jdbc:7.2.2.jre8'`

## Example

The following example demonstrates how to connect to a sqlite in memory database and how you can create some tables resembling something like twitter.

```kotlin
import io.opencubes.db.*
import io.opencubes.db.sql.CurrentTimestamp
import io.opencubes.db.sql.ISQLModelDriver

class User() : Model {
  constructor(handle: String) : this() {
    this::handle.set(handle)
  }

  val id by value<Int>().index.primary.autoIncrement
  val handle by value<String>().index.unique.string { maxLength(32) }
  var name by value<String?>().string { maxLength(128) }
  var email by value<String?>().string { maxLength(128) }
  var bio by value<String?>().string { maxLength(180) }
  private var password by value<String>().string { maxLength(64) }

  val following by referenceMany<User>()
  val followers by referenceMany(reverse = User::following)
  val upvotedPosts by referenceMany(reverse = Post::upvotes)
  val posts by referenceMany(by = Post::author)
}

class Post() : Model {
  constructor(content: String, author: User, parent: Post? = null) : this() {
    this::content.set(content)
    this::author.set(author)
    this::parent.set(parent)
  }

  val id by value<Int>().index.primary.autoIncrement
  val author by value<User>().index
  val parent by value<Post?>().index.reference { deleteAction = ForeignKeyAction.SET_NULL }
  val content by value<String>().string { maxLength(180) }
  val created by value(CurrentTimestamp)
  val repost by value(false)

  val upvotes by referenceMany<User>()
  val tags by referenceMany<Tag>()
}

class Tag() : Model {
  constructor(text: String) : this() {
    this::text.set(text)
  }

  val id by value<Int>().primary.autoIncrement
  val text by value<String>().unique.string { maxLength = 32 }

  val posts by referenceMany(reverse = Post::tags)
}

fun main() {
  // Make a SQLite in memory database and set it as the globally accessible one.
  ISQLModelDriver.connect("sqlite::memory:").setGlobal()

  // Create the tables, connection tables and calculates any differences in the
  // database and if possible corrects them.
  Model.migrate(User::class, Post::class, Tag::class)
}
```

### Generated tables in SQLite

```SQL
CREATE TABLE `users` (
  `id` INTEGER NOT NULL DEFAULT rowid,
  `bio` TEXT DEFAULT NULL,
  `email` TEXT DEFAULT NULL,
  `handle` TEXT NOT NULL,
  `name` TEXT DEFAULT NULL,
  `password` TEXT NOT NULL,

  CONSTRAINT `users_pk` PRIMARY KEY (`id`),

  CONSTRAINT `users_ux_handle` UNIQUE (`handle`)
);

CREATE INDEX `users_ix_id` ON `users` (`id`);
CREATE INDEX `users_ix_handle` ON `users` (`handle`);

CREATE TABLE `posts` (
  `id` INTEGER NOT NULL DEFAULT rowid,
  `author_id` INTEGER NOT NULL,
  `parent_id` INTEGER DEFAULT NULL,
  `content` TEXT NOT NULL,
  `created` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `repost` INTEGER NOT NULL DEFAULT 0,

  CONSTRAINT `posts_pk` PRIMARY KEY (`id`),

  CONSTRAINT `posts_fk_author_id` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `posts_fk_parent_id` FOREIGN KEY (`parent_id`) REFERENCES `posts` (`id`) ON DELETE SET NULL
);

CREATE INDEX `posts_ix_id` ON `posts` (`id`);
CREATE INDEX `posts_ix_author_id` ON `posts` (`author_id`);
CREATE INDEX `posts_ix_parent_id` ON `posts` (`parent_id`);

CREATE TABLE `tags` (
  `id` INTEGER NOT NULL DEFAULT rowid,
  `text` TEXT NOT NULL,

  CONSTRAINT `tags_pk` PRIMARY KEY (`id`),

  CONSTRAINT `tags_ux_text` UNIQUE (`text`)
);

CREATE TABLE `following` (
  `following_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `following_pk` PRIMARY KEY (`following_id`, `user_id`),

  CONSTRAINT `following_fk_following_id` FOREIGN KEY (`following_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `following_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE INDEX `following_ix_following_id` ON `following` (`following_id`);
CREATE INDEX `following_ix_user_id` ON `following` (`user_id`);

CREATE TABLE `upvotes` (
  `post_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `upvotes_pk` PRIMARY KEY (`post_id`, `user_id`),

  CONSTRAINT `upvotes_fk_post_id` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `upvotes_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE INDEX `upvotes_ix_post_id` ON `upvotes` (`post_id`);
CREATE INDEX `upvotes_ix_user_id` ON `upvotes` (`user_id`);

CREATE TABLE `post_tags` (
  `post_id` INTEGER NOT NULL,
  `tag_id` INTEGER NOT NULL,

  CONSTRAINT `post_tags_pk` PRIMARY KEY (`post_id`, `tag_id`),

  CONSTRAINT `post_tags_fk_post_id` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `post_tags_fk_tag_id` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
);

CREATE INDEX `post_tags_ix_post_id` ON `post_tags` (`post_id`);
CREATE INDEX `post_tags_ix_tag_id` ON `post_tags` (`tag_id`);
```

### Generated tables in MySQL

```SQL
CREATE TABLE `users` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `bio` VARCHAR(180) DEFAULT NULL,
  `email` VARCHAR(128) DEFAULT NULL,
  `handle` VARCHAR(32) NOT NULL,
  `name` VARCHAR(128) DEFAULT NULL,
  `password` VARCHAR(64) NOT NULL,

  CONSTRAINT `PRIMARY` PRIMARY KEY (`id`),

  CONSTRAINT `users_ux_handle` UNIQUE (`handle`),

  INDEX `users_ix_id`(`id`),
  INDEX `users_ix_handle`(`handle`)
);

CREATE TABLE `posts` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `author_id` INTEGER NOT NULL,
  `parent_id` INTEGER DEFAULT NULL,
  `content` VARCHAR(180) NOT NULL,
  `created` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `repost` BOOLEAN NOT NULL DEFAULT false,

  CONSTRAINT `PRIMARY` PRIMARY KEY (`id`),

  INDEX `posts_ix_id`(`id`),
  INDEX `posts_ix_author_id`(`author_id`),
  INDEX `posts_ix_parent_id`(`parent_id`),

  CONSTRAINT `posts_fk_author_id` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `posts_fk_parent_id` FOREIGN KEY (`parent_id`) REFERENCES `posts` (`id`) ON DELETE SET NULL
);

CREATE TABLE `tags` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `text` VARCHAR(32) NOT NULL,

  CONSTRAINT `PRIMARY` PRIMARY KEY (`id`),

  CONSTRAINT `tags_ux_text` UNIQUE (`text`)
);

CREATE TABLE `following` (
  `following_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `PRIMARY` PRIMARY KEY (`following_id`, `user_id`),

  INDEX `following_ix_following_id`(`following_id`),
  INDEX `following_ix_user_id`(`user_id`),

  CONSTRAINT `following_fk_following_id` FOREIGN KEY (`following_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `following_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE TABLE `upvotes` (
  `post_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `PRIMARY` PRIMARY KEY (`post_id`, `user_id`),

  INDEX `upvotes_ix_post_id`(`post_id`),
  INDEX `upvotes_ix_user_id`(`user_id`),

  CONSTRAINT `upvotes_fk_post_id` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `upvotes_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE TABLE `post_tags` (
  `post_id` INTEGER NOT NULL,
  `tag_id` INTEGER NOT NULL,

  CONSTRAINT `PRIMARY` PRIMARY KEY (`post_id`, `tag_id`),

  INDEX `post_tags_ix_post_id`(`post_id`),
  INDEX `post_tags_ix_tag_id`(`tag_id`),

  CONSTRAINT `post_tags_fk_post_id` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `post_tags_fk_tag_id` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
);
```

[wiki-ar]: https://en.wikipedia.org/wiki/Active_record_pattern
