@MVP.DISTANT.FUTURE
Feature: Flows Can be created, deleted and updated

  In their simplest form flows can belong to a single switch and consist of
  just two ports: ingress and egress. Traffic in such flows can be tagged
  with a specific vlan id on input, on output, on both or not tagged at all.
  More complex flows can consist of two or more switches.  The input and
  output tags can differ. Scenarios below make sure that such flows could be
  created, modified and eventually deleted correctly and that traffic
  actually obeys the rules installed.

  Scenario Outline: A Flow can be created and then deleted

    Given a clean controller
      And controlled network ready for testing
     Then <flow_flavor> flow can be created
      And traffic can flow through this flow
     Then this flow can be deleted
      And no traffic flows through this flow

    Examples: Possible Flows For Single Switch Network
      | flow_flavor                      |
      | single switch not tagged         |
      | single switch same tag           |
      | single switch differently tagged |
      | single switch tagged untagged    |
      | single switch untagged tagged    |

    Examples: Possible Flows For Two-Switch Network
      | flow_flavor                      |
      | two switches not tagged          |
      | two switches same tag            |
      | two switches differently tagged  |
      | two switches tagged untagged     |
      | two switches untagged tagged     |

    Examples: Possible Flows For Multi-Switch Network
      | flow_flavor                      |
      | many switches not tagged         |
      | many switches same tag           |
      | many switches differently tagged |
      | many switches tagged untagged    |
      | many switches untagged tagged    |
