# Test File

This has valid markdown but invalid beancount in the block.

```beancount
2023-01-01 * "Test" "Invalid posting"
  Assets:Checking 10.00 USD
  Expenses:Food not_a_number USD
```

And some more markdown.
