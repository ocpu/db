@file:Suppress("SpellCheckingInspection", "unused", "DuplicatedCode")

import io.opencubes.db.*

object SocialPostMedia {
  fun generateSalt() = "(new salt)"

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
//  val followingPosts by receiveMany(from = User::follows, mapper = Post::author)
//    .orderBy(Post::created)

    fun newPassword(newPassword: String) {
      password = newPassword // Some kind of hashing
    }
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

  class FullPost : Composite {
    val handle by User::handle
    val name by User::name
    val authorId by User::id
    val postId by Post::id
    val content by Post::content
    val created by Post::created
    val upvotes by count(Post::upvotes)
  }

  @JvmStatic
  fun main(args: Array<String>) {
    IModelDriver.connect("sqlite::memory:").setGlobal()
//    IModelDriver.connect("mysql://localhost/socialmedia", user = "ocpu", password = System.getenv("DB_PASS")).setGlobal()
    Model.migrate(User::class, Post::class, Tag::class)

    val alice = User("alice")
    alice.newPassword("alicepass")
    alice.name = "Alice"
    alice.email = "alice@example.com"
    alice.bio = "I am the test user Alice."
    alice.save()

    val post = Post("This is my first post. #firstpost", alice)
    post.save()

    val firstTag = Tag("firstpost")
    firstTag.save()

    post.tags.add(firstTag)

    val bob = User("bob")
    bob.newPassword("bobpass")
    bob.name = "Bob"
    bob.email = "bob@example.com"
    bob.bio = "I am the test user bob."
    bob.save()

    bob.following.add(alice)
    bob.followers.add(alice)

    bob.upvotedPosts.add(post)
    post.upvotes.add(alice)

    val outputPost = Composite.find(FullPost::postId to post.id)
    if (outputPost != null) {
      println("By: ${outputPost.name ?: outputPost.handle}")
      println("Upvotes: ${outputPost.upvotes}")
      println(outputPost.content)
    } else println("outputPost was not found")
  }
}
