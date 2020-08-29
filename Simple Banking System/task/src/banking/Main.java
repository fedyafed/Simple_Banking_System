package banking;

import org.sqlite.SQLiteDataSource;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;
import java.util.Scanner;

public class Main {
    //    private static final long IIN = 4000_00__00_0000_0000L;

    public static void main(String[] args) {
        String dbFileName = null;

        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].equalsIgnoreCase("-fileName")) {
                System.out.println("Filename should be passed to the program using -fileName argument");
                return;
            }
            dbFileName = args[i + 1];
        }

        if (dbFileName == null) {
            System.out.println("Filename should be passed to the program using -fileName argument");
            return;
        }

        try (AccountDAO accountDAO = new AccountDAO(dbFileName)) {
            final AccountService accountService = new AccountService(accountDAO);
            final AccountUI accountUI = new AccountUI(accountService);

            accountUI.run();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }
}

class AccountUI implements Runnable {
    private final Scanner scanner = new Scanner(System.in);
    private final AccountService accountService;
    private Account currentAccount;

    AccountUI(AccountService accountService) {
        this.accountService = accountService;
    }

    public void run() {
        try {
            boolean keepRunning = true;
            do {
                System.out.println(
                        "1. Create an account\n" +
                                "2. Log into account\n" +
                                "0. Exit");
                switch (scanner.nextInt()) {
                    case 0:
                        keepRunning = false;
                        break;
                    case 1:
                        createAccount();
                        break;
                    case 2:
                        keepRunning = accountOperations();
                        currentAccount = null;
                }
            } while (keepRunning);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Bye!");
    }

    private void createAccount() throws SQLException {
        final Account account = accountService.createAccount();
        System.out.printf(
                "Your card has been created\n" +
                        "Your card number:\n" +
                        "%s\n" +
                        "Your card PIN:\n" +
                        "%04d\n",
                account.getNumber(),
                account.getPin());
    }

    private boolean accountOperations() throws SQLException {
        System.out.println("Enter your card number:");
        final String number = scanner.next();
        System.out.println("Enter your PIN:");
        final int pin = scanner.nextInt();
        currentAccount = accountService.loginToAccount(number, pin);
        if (currentAccount == null) {
            System.out.println("Wrong card number or PIN!");
            return true;
        }
        System.out.println("You have successfully logged in!");

        do {
            System.out.println(
                    "1. Balance\n" +
                            "2. Add income\n" +
                            "3. Do transfer\n" +
                            "4. Close account\n" +
                            "5. Log out\n" +
                            "0. Exit");
            switch (scanner.nextInt()) {
                case 0:
                    return false;
                case 1:
                    System.out.println("Balance: " + currentAccount.getBalance());
                    break;
                case 2:
                    addIncome();
                    break;
                case 3:
                    transfer();
                    break;
                case 4:
                    closeAccount();
                    return true;
                case 5:
                    System.out.println("You have successfully logged out!");
                    return true;
            }
        } while (true);
    }

    private void transfer() throws SQLException {
        System.out.println("Transfer\n" +
                "Enter card number:");

        final long transferNumber = scanner.nextLong();
        if (accountService.checksum(transferNumber) != 0) {
            System.out.println("Probably you made mistake in the card number. Please try again!");
            return;
        }

        final Account transferAccount = accountService.getAccount(String.valueOf(transferNumber));
        if (transferAccount == null) {
            System.out.println("Such a card does not exist.");
            return;
        }

        System.out.println("Enter how much money you want to transfer:");
        final int amount = scanner.nextInt();
        if (currentAccount.getBalance() < amount) {
            System.out.println("Not enough money!");
            return;
        }

        accountService.transfer(currentAccount, transferAccount, amount);
        System.out.println("Success!");
    }

    private void closeAccount() throws SQLException {
        accountService.closeAccount(currentAccount);
        System.out.println("The account has been closed!");
    }

    private void addIncome() throws SQLException {
        System.out.println("Enter income:");
        final int income = scanner.nextInt();
        accountService.addIncome(currentAccount, income);
        System.out.println("Income was added!");
    }

}

class AccountService {
    private final AccountDAO accountDAO;
    private final Random random = new Random();
    private final String iin = "400000";

    AccountService(AccountDAO accountDAO) {
        this.accountDAO = accountDAO;
    }

    public Account getAccount(String number) throws SQLException {
        return accountDAO.getAccount(number);
    }

    public Account loginToAccount(String number, int pin) throws SQLException {
        final Account account = accountDAO.getAccount(number);
        if (account != null && account.getPin() == pin) {
            return account;
        }
        return null;
    }

    public Account createAccount() throws SQLException {
        long id;
        String number;
        do {
            id = random.nextInt(10_0000_0000);
            number = String.format("%s%09d", iin, id);
            number += checksum(Long.parseLong(number) * 10);
        } while (accountDAO.getAccount(number) != null);

        int pin = generatePin();
        final Account account = new Account(id, number, pin);
        accountDAO.createAccount(account);

        return account;
    }


    private int generatePin() {
        return random.nextInt(10_000);
    }

    public int checksum(long id) {
        if (id < 1) {
            return 0;
        }

        int i = (int) Math.log10(id) + 1;
        long reminder = id;
        int sum = 0;
        while (reminder > 0) {
            int digit = (int) (reminder % 10);
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            reminder /= 10;
            i--;
        }

        return 9 - ((sum + 9) % 10);
    }

    public void addIncome(Account account, int income) throws SQLException {
        final int balance = account.getBalance();
        account.setBalance(balance + income);
        accountDAO.updateAccount(account);
    }

    public void closeAccount(Account account) throws SQLException {
        accountDAO.deleteAccount(account);
    }

    public void transfer(Account fromAccount, Account toAccount, int amount) throws SQLException {
        addIncome(toAccount, amount);
        addIncome(fromAccount, -amount);
    }
}

class AccountDAO implements Closeable {
    private Connection connection;

    AccountDAO(String dbFileName) throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFileName);
        connection = dataSource.getConnection();

        initDB();
    }

    private void initDB() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS card (\n" +
                    "id INTEGER UNIQUE NOT NULL,\n" +
                    "number TEXT UNIQUE NOT NULL,\n" +
                    "pin TEXT NOT NULL,\n" +
                    "balance INTEGER DEFAULT 0 NOT NULL CHECK (balance >= 0)\n" +
                    ")");
        }
    }

    public void createAccount(Account account) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    String.format("INSERT INTO card VALUES (%d, '%s', '%04d', %d)",
                            account.getId(),
                            account.getNumber(),
                            account.getPin(),
                            account.getBalance()
                    ));
        }
    }

    public Account getAccount(String number) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(
                    String.format("SELECT * \n" +
                                    "FROM card \n" +
                                    "WHERE number = '%s'",
                            number))) {
                if (resultSet.next()) {
                    return new Account(
                            resultSet.getLong("id"),
                            resultSet.getString("number"),
                            resultSet.getInt("pin"),
                            resultSet.getInt("balance")
                    );
                }
            }
        }
        return null;
    }

    public void updateAccount(Account account) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    String.format("UPDATE card \n" +
                                    "SET balance = %d\n" +
                                    "WHERE number = '%s'",
                            account.getBalance(),
                            account.getNumber()
                    ));
        }
    }

    public void deleteAccount(Account account) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    String.format("DELETE FROM card \n" +
                                    "WHERE number = '%s'",
                            account.getNumber()
                    ));
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                connection = null;
            }
        }
    }
}

class Account {
    private final long id;
    private final String number;
    private final int pin;
    private int balance = 0;

    public Account(long id, String number, int pin) {
        this.id = id;
        this.number = number;
        this.pin = pin;
    }

    public Account(long id, String number, int pin, int balance) {
        this.id = id;
        this.number = number;
        this.pin = pin;
        this.balance = balance;
    }

    public long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public int getPin() {
        return pin;
    }

    public int getBalance() {
        return balance;
    }

    public void setBalance(int balance) {
        this.balance = balance;
    }
}
