A super charged database connection with active record.

- [General idea](#general-idea)
- [Database](#database)
- [Active Record](#active-record)
- [Example](#example)
- [Features](#features)

## General idea

I created this mainly a side project to make a interface with a database where there is no user created sql. If there was user created sql it would be easily executed.

As such there are 2 classes. One holds the simplified database connection and the other being the active record class.

## Database

The database class holds a connection to any database type. Creating a connection is by calling one of the constructors.

1. `io.opencubes.sql.Databse(dsn string)`
2. `io.opencubes.sql.Databse(dsn string, user string, password string)`

Since this class only simplifies the general connection functions it does not implement any connectors/drivers for the diffrent database types. But here is a not so complete list of the existing ones (Gradle):

- MySQL
  - `compile "mysql:mysql-connector-java:8.0.11"`
  - `compile group: "mysql", name: "mysql-connector-java", version: "8.0.11"`
- SQLite
  - `compile "org.xerial:sqlite-jdbc:3.25.2"`
  - `compile group: "org.xerial", name: "sqlite-jdbc", version: "3.25.2"`

## Active Record

This is a implementation of the [Active Record Pattern][wiki-ar] with the extra feature of creating the table from the class. Creating the table from a class can be usefull when you want to create tables from code. Even if you don't use that feature the delegates can describe more of you strucrure to the one looking at the class.

## Example

The following example demonstrates how to connect to a sqlite in memory database and how you can create some tables

```kotlin
import java.sql.Timestamp
import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Database
import io.opencubes.sql.SerializedName

val db = Database("sqlite::memory:").asGlobal

class User : ActiveRecord() {
  val id by primaryKey()
  @Index
  var handle by value<String>()
  var name by value<String?>()
  var email by value<String?>()
  var password by value<String>()
  var salt by value {/* generate salt */}
  var bio by value<String?>()
  val follows by referenceMany<User>()
  val followers by referenceMany<User>(table = "follows", key = "follows_id", referenceKey = "user_id")
  val posts by referenceMany(Post::author)

  fun updatePassword(password: String) {/* code */}

  fun verifyPassword(password: String): Boolean { /* code */ return true }

  companion object {
    fun get(id: Int) = find(User::id, id)
  }
}

class Post : ActiveRecord() {
  val id by primaryKey()
  var content by value<String>()
  val created by value<Timestamp>(Database::CurrentTimestamp)
  var author by reference<User>()
  var parent by referenceNull<Post>()
  val likes by referenceMany<User>()
  val tags by referenceMany<Tag>()

  companion object {
    @JvmStatic
    fun new(author: User, content: String, parent: Post? = null): Post {
      val post = Post()
      post.content = content
      post.parent = parent
      post.author = author
      post.save()

      Regex("""#(\w+)""").findAll(content).forEach {
        val tagContent = it.groupValues[1]
        val tag = ActiveRecord.find(Tag::content, tagContent) ?: Tag().apply {
          this.content = tagContent
          save()
        }
        post.tags.add(tag)
      }
      return post
    }
  }
}

class Tag : ActiveRecord() {
  val id by primaryKey()
  var content by value<String>()
  
  val posts by referenceMany<Post>()
}

fun main(args: Array<String>) {
  ActiveRecord.create(User::class, Post::class, Tag::class)

  val user = User()
  user.handle = "ocpu"
  user.name = "Martin Hövre"
  user.updatePassword("pass")
  user.save()
  
  val ocpu = ActiveRecord.find(User::handle, "ocpu") ?: return

  if (ocpu.verifyPassword("pass")) {
    val post = Post.new(ocpu, "Hello, world!")
  }
}
```

## Features

| Feature                       | Status |
| :---------------------------- | :----: |
| Simple SQL execution          |   ✓    |
| Database Agnostic SQL         |   ✓    |
| Result Set Wrapper            |   ✓    |
| Active Record                 |   ✓    |
| Many To Many Field            |   ✓    |
| One To Many Field             |   ✓    |
| Automatic Table Names         |   ✓    |
| Table Creation SQL            |   ✓    |
| Find single object            |   ✓    |
| Find multiple objects         |   ✓    |
| Search single object          |   ✓    |
| Search multiple objects       |   ✓    |
| Table Migration SQL           |   ✕    |
| Custom select query for model |   ✕    |

[wiki-ar]: https://en.wikipedia.org/wiki/Active_record_pattern
