# db
A super charged database connection with active record.

## General idea

I created this mainly a side project to make a interface with a database where there is no user created sql. If there was user created sql it would be easily executed.

As such there are 2 classes. One holds the simplified database connection and the other being the active record class.

## Database

The database class holds a connection to any database type. Creating a connection is by calling one of the constructors.

1. `io.opencubes.sql.Databse(dsn string)`
2. `io.opencubes.sql.Databse(dsn string, user string, password string)`

Since this class only simplifies the general connection functions it does not implement any connectors/drivers for the diffrent database types. But here is a not so complete list of the existing ones (Gradle):

- MySQL: `compile "mysql:mysql-connector-java:8.0.11"`
- SQLite: `compile "org.xerial:sqlite-jdbc:3.25.2"`

## Active Record

This is a implementation of the [Active Record Pattern][wiki-ar] with the extra feature of creating the table from the class. Creating the table from a class can be usefull when you want to create tables from code. Even if you don't use that feature the delegates can describe more of you strucrure to the one looking at the class.

## Example

The following example demonstrates how to connect to a sqlite in memory database and how you can create some tables

```kotlin
import java.sql.Timestamp
import io.opencubes.sql.ActiveRecord
import io.opencubes.sql.Database
import io.opencubes.sql.SerializedName

val db = Database("sqlite::memory:")

class User : ActiveRecord(db, "users") {
  @Auto
  val id by value<Int>()
  @Index
  var handle by value<String>()
  var name by value<String>()
  var password by value<String>()

  fun updatePassword(password: String) { /* code */ }
  fun verifyPassword(password: String): Boolean { /* code */ }
}

class Post : ActiveRecord(db, "posts") {
  @Auto
  val id by value<Int>()
  @SerializedName("user_id")
  val authorId by reference(User::id)
  var content by value<String>()
  val created by value<Timestamp> { Database.CurrentTimestamp() }
}

fun main(args: Array<String>) {
  ActiveRecord.createTable<User>()
  ActiveRecord.createTable<Post>()

  val user = User()
  user.handle = "ocpu"
  user.name = "Martin Hövre"
  user.updatePassword("pass")
  user.save()

  val ocpu = ActiveRecord.find<User>("handle", "ocpu") ?: return

  if (ocpu.verifyPassword("pass")) {
    val post = Post()
    post.authorId = ocpu.id
    post.content = "Hello, world!"
    post.save()
  }
}
```

[wiki-ar]: https://en.wikipedia.org/wiki/Active_record_pattern
