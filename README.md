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
import io.opencubes.sql.ISQLModelDriver
import io.opencubes.sql.Database

fun generateSalt(): String = "(SALT)"

class User() : Model() {
  constructor(handle: String) : this() {
    this::handle.set(handle)
  }

  val id by value<Int>().index.primary.autoIncrement
  val handle by value<String>().index.unique.string { maxLength(32) }
  var name by value<String?>().string { maxLength(128) }
  var email by value<String?>().string { maxLength(128) }
  var bio by value<String?>().string { maxLength(180) }
  private val salt by value(::generateSalt).unique("password").string { maxLength(64) }
  private var password by value<String>().unique("password").string { maxLength(64) }

  val following by referenceMany<User>()
  val followers by referenceMany(reverse = User::following)
  val likedTweets by referenceMany(reverse = Tweet::likes)
  val tweets by referenceMany(by = Tweet::author)

  fun newPassword(newPassword: String) {
    password = salt + newPassword
  }
}

class Tweet() : Model() {
  constructor(content: String, author: User, parent: Tweet? = null) : this() {
    this::content.set(content)
    this::author.set(author)
    this::parent.set(parent)
  }

  val id by value<Int>().index.primary.autoIncrement
  val author by value<User>().index
  val parent by value<Tweet?>().index.reference { deleteAction = ForeignKeyAction.SET_NULL }
  val content by value<String>().string { maxLength(180) }
  val created by value(CurrentTimestamp)
  val retweet by value(false)

  val likes by referenceMany<User>()
  val tags by referenceMany<Tag>()
}

class Tag() : Model() {
  constructor(text: String) : this() {
    this::text.set(text)
  }

  val id by value<Int>().primary.autoIncrement
  val text by value<String>().unique.string { maxLength = 32 }

  val tweets by referenceMany(reverse = Tweet::tags)
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
  `salt` TEXT NOT NULL,

  CONSTRAINT `users_pk` PRIMARY KEY (`id`),

  CONSTRAINT `users_ux_handle` UNIQUE (`handle`),
  CONSTRAINT `users_ux_password` UNIQUE (`password`, `salt`)
);

CREATE INDEX `users_ix_id` ON `users` (`id`);
CREATE INDEX `users_ix_handle` ON `users` (`handle`);

CREATE TABLE `tweets` (
  `id` INTEGER NOT NULL DEFAULT rowid,
  `author_id` INTEGER NOT NULL,
  `parent_id` INTEGER DEFAULT NULL,
  `content` TEXT NOT NULL,
  `created` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `retweet` INTEGER NOT NULL DEFAULT 0,

  CONSTRAINT `tweets_pk` PRIMARY KEY (`id`),

  CONSTRAINT `tweets_fk_author_id` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `tweets_fk_parent_id` FOREIGN KEY (`parent_id`) REFERENCES `tweets` (`id`) ON DELETE SET NULL
);

CREATE INDEX `tweets_ix_id` ON `tweets` (`id`);
CREATE INDEX `tweets_ix_author_id` ON `tweets` (`author_id`);
CREATE INDEX `tweets_ix_parent_id` ON `tweets` (`parent_id`);

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

CREATE TABLE `likes` (
  `tweet_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `likes_pk` PRIMARY KEY (`tweet_id`, `user_id`),

  CONSTRAINT `likes_fk_tweet_id` FOREIGN KEY (`tweet_id`) REFERENCES `tweets` (`id`) ON DELETE CASCADE,
  CONSTRAINT `likes_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE INDEX `likes_ix_tweet_id` ON `likes` (`tweet_id`);
CREATE INDEX `likes_ix_user_id` ON `likes` (`user_id`);

CREATE TABLE `tag_tweets` (
  `tag_id` INTEGER NOT NULL,
  `tweet_id` INTEGER NOT NULL,

  CONSTRAINT `tag_tweets_pk` PRIMARY KEY (`tag_id`, `tweet_id`),

  CONSTRAINT `tag_tweets_fk_tag_id` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE,
  CONSTRAINT `tag_tweets_fk_tweet_id` FOREIGN KEY (`tweet_id`) REFERENCES `tweets` (`id`) ON DELETE CASCADE
);

CREATE INDEX `tag_tweets_ix_tag_id` ON `tag_tweets` (`tag_id`);
CREATE INDEX `tag_tweets_ix_tweet_id` ON `tag_tweets` (`tweet_id`);
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
  `salt` VARCHAR(64) NOT NULL,

  CONSTRAINT `users_pk` PRIMARY KEY (`id`),

  CONSTRAINT `users_ux_handle` UNIQUE (`handle`),
  CONSTRAINT `users_ux_password` UNIQUE (`password`, `salt`),

  INDEX `users_ix_id`(`id`),
  INDEX `users_ix_handle`(`handle`)
);

CREATE TABLE `tweets` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `author_id` INTEGER NOT NULL,
  `parent_id` INTEGER DEFAULT NULL,
  `content` VARCHAR(180) NOT NULL,
  `created` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `retweet` BOOLEAN NOT NULL DEFAULT false,

  CONSTRAINT `tweets_pk` PRIMARY KEY (`id`),

  INDEX `tweets_ix_id`(`id`),
  INDEX `tweets_ix_author_id`(`author_id`),
  INDEX `tweets_ix_parent_id`(`parent_id`),

  CONSTRAINT `tweets_fk_author_id` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`),
  CONSTRAINT `tweets_fk_parent_id` FOREIGN KEY (`parent_id`) REFERENCES `tweets` (`id`) ON DELETE SET NULL
);

CREATE TABLE `tags` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `text` VARCHAR(32) NOT NULL,

  CONSTRAINT `tags_pk` PRIMARY KEY (`id`),

  CONSTRAINT `tags_ux_text` UNIQUE (`text`)
);

CREATE TABLE `following` (
  `following_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `following_pk` PRIMARY KEY (`following_id`, `user_id`),

  INDEX `following_ix_following_id`(`following_id`),
  INDEX `following_ix_user_id`(`user_id`),

  CONSTRAINT `following_fk_following_id` FOREIGN KEY (`following_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `following_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE TABLE `likes` (
  `tweet_id` INTEGER NOT NULL,
  `user_id` INTEGER NOT NULL,

  CONSTRAINT `likes_pk` PRIMARY KEY (`tweet_id`, `user_id`),

  INDEX `likes_ix_tweet_id`(`tweet_id`),
  INDEX `likes_ix_user_id`(`user_id`),

  CONSTRAINT `likes_fk_tweet_id` FOREIGN KEY (`tweet_id`) REFERENCES `tweets` (`id`) ON DELETE CASCADE,
  CONSTRAINT `likes_fk_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
);

CREATE TABLE `tag_tweets` (
  `tag_id` INTEGER NOT NULL,
  `tweet_id` INTEGER NOT NULL,

  CONSTRAINT `tag_tweets_pk` PRIMARY KEY (`tag_id`, `tweet_id`),

  INDEX `tag_tweets_ix_tag_id`(`tag_id`),
  INDEX `tag_tweets_ix_tweet_id`(`tweet_id`),

  CONSTRAINT `tag_tweets_fk_tag_id` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE,
  CONSTRAINT `tag_tweets_fk_tweet_id` FOREIGN KEY (`tweet_id`) REFERENCES `tweets` (`id`) ON DELETE CASCADE
);
```

[wiki-ar]: https://en.wikipedia.org/wiki/Active_record_pattern
