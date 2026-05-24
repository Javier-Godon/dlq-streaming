Feature: Drain dead-letter records
  The DLQ drain must move PostgreSQL dead-letter records to a receiver safely.

  Scenario: Successfully drain claimed records
    Given the dead-letter table has pending records
      | processId                         |
      | product-1_2026-05-23T10:15:30Z    |
      | product-2_2026-05-23T10:16:30Z    |
    And the receiver accepts every record
    When the drain runs with batch size 10
    Then 2 records are received
    And 2 records are deleted from the dead-letter table
    And the drain does not stop because the receiver failed

  Scenario: Stop immediately when the receiver fails
    Given the dead-letter table has pending records
      | processId                         |
      | product-1_2026-05-23T10:15:30Z    |
      | product-2_2026-05-23T10:16:30Z    |
      | product-3_2026-05-23T10:17:30Z    |
    And the receiver fails on record number 2
    When the drain runs with batch size 10
    Then 2 records are received
    And 1 records are deleted from the dead-letter table
    And the drain stops because the receiver failed at "product-2_2026-05-23T10:16:30Z"

  Scenario: Nothing to drain when the dead-letter table is empty
    Given the dead-letter table is empty
    And the receiver accepts every record
    When the drain runs with batch size 10
    Then 0 records are received
    And 0 records are deleted from the dead-letter table
    And the drain does not stop because the receiver failed

  Scenario: Batch size limits the number of records claimed per run
    Given the dead-letter table has pending records
      | processId                         |
      | product-1_2026-05-23T10:15:30Z    |
      | product-2_2026-05-23T10:16:30Z    |
      | product-3_2026-05-23T10:17:30Z    |
      | product-4_2026-05-23T10:18:30Z    |
      | product-5_2026-05-23T10:19:30Z    |
    And the receiver accepts every record
    When the drain runs with batch size 3
    Then 3 records are received
    And 3 records are deleted from the dead-letter table
    And the drain does not stop because the receiver failed

