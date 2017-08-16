![Corda](https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png)

# Option CorDapp Version 1

This repo contains a CorDapp representing an OTC Equity Option. The following features have been added:

* The code has been rebased to Corda M12.1
* Nodes can self issue cash
* Nodes can transfer Options to other nodes
* Node's can exercise Options with payment settled in the form of an obligation
* The web UI facilitates issuance, transferance and exercising of Options

# Assumptions

* The issuance cannot go into default
* The option owner only signs the exercise transactions (therefore they cannot be blocked by the issuer)
* In a real-world app, we would have to introduce some complexities to deal with the possibility of the issuer defaulting. Perhaps an encumbrance would be placed on some amount, e.g. in the case of issuing a PUT, it would be in the amount of the strike

# Pre-requisites:
  
* JDK 1.8 minimum version (1.8.131)
* IntelliJ minimum version (2017.1) 
* git

# Usage

## Running the nodes:

* Ensure Oracle JDK 1.8 is installed.
* `cd` to the directory where you want to clone this repo
* `git clone http://github.com/roger3cev/iou-cordapp-v2`
* `cd iou-cordapp-v2`
* `./gradlew deployNodes`
* `cd build/nodes`
* `./runnodes`

## Using the CorDapp:

Via the web: 

Navigate to http://localhost:PORT/web/iou to use the web interface where PORT typically starts at 10007 for NodeA, double check the node terminal window or the build.gradle file for port numbers.

Via the node shell from any node which is not the **Controller**: 
