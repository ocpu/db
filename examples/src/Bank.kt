import io.opencubes.db.*

@Suppress("RemoveExplicitTypeArguments", "unused")
object Bank {
  class User : Model {
    val id by value<Int>().primary.autoIncrement
    val username by value<String>().unique.maxLength(64)
  }

  class Account : Model {
    val id by value<Int>().primary.autoIncrement
    val name by value<String>().maxLength(64)
    var balance by value<Double>(0.0)

    val owners by referenceMany<User>()
    val transactionLog by referenceMany<Transaction>(by = Transaction::account)

    fun withdraw(amount: Double, msg: String) {
      check(amount > 0)
      balance -= amount
      val transaction = Transaction.createWithdraw(this, amount, msg)
      transaction.save()
      transactionLog.refresh()
    }
  }

  class Transaction : Model {
    val message by value<String>()
    val amount by value<Double>()
    val action by value<Action>()
    val account by value<Account>()

    companion object {
      fun createDeposit(account: Account, amount: Double, msg: String): Transaction {
        return Transaction().apply {
          this::account.set(account)
          this::amount.set(amount)
          this::message.set(msg)
          this::action.set(Action.DEPOSIT)
        }
      }
      fun createWithdraw(account: Account, amount: Double, msg: String): Transaction {
        return Transaction().apply {
          this::account.set(account)
          this::amount.set(amount)
          this::message.set(msg)
          this::action.set(Action.WITHDRAW)
        }
      }
    }

    enum class Action {
      DEPOSIT, WITHDRAW
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    IModelDriver.connect("sqlite::memory:").setGlobal()
//    IModelDriver.connect("mysql://localhost/bank", user = "ocpu", password = System.getenv("DB_PASS")).setGlobal()
    Model.migrate(User::class, Account::class, Transaction::class)
  }
}
