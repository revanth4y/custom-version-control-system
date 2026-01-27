# Custom Version Control System

A Git-inspired **version control backend** designed to demonstrate how core version control workflows operate internally.  
This project focuses on **system design, correctness, and backend fundamentals**, rather than re-implementing Git completely.

---

## 📌 Overview

This system provides a structured alternative to manual file versioning by implementing:

- Hash-based change tracking
- Commit history management
- Secure repository access
- Filesystem-based persistence

It is built as a backend service and is intended for **learning, interviews, and backend design demonstrations**.

---

## ✨ Key Features

- Repository initialization
- File staging (indexing)
- Commit creation and commit history
- Hash-based version snapshots
- Rollback and revert functionality
- Authentication and role-based authorization
- RESTful API design for versioning operations

---

## 🛠️ Tech Stack

- **Backend:** Node.js, Express.js
- **Language:** JavaScript
- **Storage:** File system–based persistence
- **Security:** Authentication & Authorization
- **Concepts:**  
  - Hashing (SHA)  
  - REST APIs  
  - Version Control Fundamentals  

---

## 🧠 System Design Highlights

- Uses **hash-based identifiers** to track file versions and commits
- Stores version snapshots using a **structured filesystem layout**
- Separates concerns using a **controller-based architecture**
- Secures repository operations with **role-based access control**
- Designed to support **clear rollback and recovery workflows**

---

## 📂 Project Structure (High Level)

+-- controllers
|   +-- init.js
|   +-- add.js
|   +-- commit.js
|   +-- revert.js
|   +-- history.js
|
+-- middleware
|   +-- authMiddleware.js
|   +-- authorizeMiddleware.js
|
+-- storage
|   +-- commits
|   +-- index
|
+-- routes
|
+-- index.js
+-- README.md



## 🔁 Example Workflow

1. Initialize a repository  
2. Stage files for tracking  
3. Commit changes to generate a version snapshot  
4. View commit history  
5. Revert or rollback to a previous version  

---

## 🎯 Use Cases

This system is intended for:

- Learning how version control systems work internally
- Demonstrating backend and system design skills
- Practicing REST API design and filesystem-based persistence
- Interview discussions around Git fundamentals

---

## ⚠️ Limitations

- Not a full replacement for Git
- No distributed peer-to-peer synchronization
- Focuses on backend logic rather than UI

These design choices were intentional to keep the system **simple, understandable, and interview-friendly**.

---

## 🚀 Future Improvements

- Branching and merge support
- Distributed repository synchronization
- Command-line interface (CLI)
- Optimized storage using delta compression

---

## 👤 Author

**Revanth Y**  
GitHub: https://github.com/revanth4y

