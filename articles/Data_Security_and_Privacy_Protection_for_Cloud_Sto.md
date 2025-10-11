# Summary: *Data Security and Privacy Protection for Cloud Storage: A Survey*  
*(IEEE Access, 2020 – DOI: 10.1109/ACCESS.2020.3009876)*  
**Authors:** Pan Yang, Naixue Xiong, Jingli Ren  

---

## 1. Overview

This paper presents a **comprehensive survey of data security and privacy protection techniques** in **cloud storage systems**, examining:
- Key challenges and threats.
- Cryptographic mechanisms for protection.
- Open research directions, including post-quantum and privacy-preserving cloud machine learning.

Cloud storage has become fundamental due to **IoT, smart cities, and digital transformation**, but brings significant **security and privacy risks** such as:
- Unauthorized access
- Data leakage
- Integrity compromise
- Malicious cloud providers

---

## 2. Cloud Storage: Architecture and Security Requirements

### **Classification**
- **Public Cloud:** Outsourced data (AWS, Alibaba Cloud); scalable but trust-dependent.
- **Private Cloud:** Internal enterprise deployment; higher control but costly.
- **Hybrid Cloud:** Combines public and private benefits.
- **Community Cloud:** Shared among organizations with common concerns (e.g., healthcare, finance).

### **Architecture Layers**
1. **Storage Layer** – Physical storage and management.  
2. **Primary Management Layer** – Core management of data and metadata.  
3. **Application Interface Layer** – Exposes APIs and services.  
4. **Access Layer** – Authentication, computation, and access control.

### **Core Security Requirements**
| Requirement | Description |
|--------------|-------------|
| **Confidentiality** | Prevent unauthorized access to stored or transmitted data. |
| **Integrity** | Ensure data is not altered or corrupted. |
| **Availability** | Maintain continuous and reliable data access. |
| **Fine-Grained Access Control** | Enforce user-level permissions. |
| **Dynamic Secure Sharing** | Support secure group sharing. |
| **Leakage Resistance** | Prevent side-channel and indirect information leakage. |
| **Complete Deletion** | Guarantee irreversible data removal. |
| **Privacy Protection** | Protect users’ identity, location, and sensitive attributes. |

---

## 3. Cryptography-Based Security Technologies

### **3.1 Identity-Based Encryption (IBE)**
- Introduced by Shamir (1984); formalized by Boneh & Franklin (2001).  
- Public key derived from a user’s identity (e.g., email).  
- Simplifies key management, removes certificate verification.  
- Used in **revocable schemes** and **proxy re-encryption**.  
- Enhancements include **outsourced computation** and **searchable storage IBE** (Wei et al. 2015).

### **3.2 Attribute-Based Encryption (ABE)**
- Extends IBE: users’ **attributes** (roles, departments) determine access.  
- Enables **fine-grained access control** and **data sharing**.  
- Two types:
  - **KP-ABE:** Policy in key, attributes in ciphertext.  
  - **CP-ABE:** Policy in ciphertext, attributes in key (common in cloud sharing).  
- Supports **user revocation**, **policy compacting**, and **outsourced decryption** for resource-limited users.  
- Used for **multi-user collaboration**, **group access**, and **dynamic policy updates**.

### **3.3 Homomorphic Encryption (HE)**
- Allows computation on encrypted data without decryption.  
- **Partial (PHE)** → one operation (add or multiply).  
- **Somewhat (SHE)** → limited operations.  
- **Fully Homomorphic (FHE)** → unlimited operations (Gentry 2009).  
- Enables **privacy-preserving cloud computation** and **secure machine learning**.

### **3.4 Searchable Encryption (SE)**
- Enables keyword-based search over encrypted data.
- Two main types:
  - **SSE (Symmetric):** Efficient for single-owner models.
  - **PEKS (Public Key Encryption with Keyword Search):** Supports public-key settings.
- Enhancements:  
  - **Ranked searches** (by relevance).  
  - **Multi-keyword search** with fuzzy matching.  
  - **Verifiable and aggregate key-based SE** to reduce user key load.

---

## 4. State-of-the-Art Research Directions

### **4.1 One-to-Many Encryption & Access Control**
- Enables data sharing from one owner to multiple authorized users.
- Features:
  - **Collusion-resistance**
  - **Dynamic access control**
  - **Break-glass access** (emergency temporary access, e.g., in healthcare).
- Example: Self-adaptive access with secure deduplication (Yang et al. 2020).

### **4.2 Data Integrity**
- Ensures outsourced data remains unaltered.
- Techniques:
  - **Provable Data Possession (PDP)** – Ateniese et al.
  - **Proof of Retrievability (PoR)** – Shacham & Waters.
  - **Public auditing** using Third-Party Auditors (TPA).
- Approaches:
  - **PKI-based auditing** (Shen et al.)
  - **ID-based auditing** (Zhang & Dong)
  - **Certificateless and lightweight PDP** for smart devices.

### **4.3 Data Deletion**
- True deletion is crucial since cloud servers often only perform logical deletion.
- **Merkle Hash Tree (MHT)** used to verify **secure and irreversible deletion**.
- Examples:  
  - Attribute-revocation-based deletion (Xue et al. 2019).  
  - Rank-based MHT validation (Yang et al. 2019).

### **4.4 Leakage-Resilient Cryptography**
- Defends against **side-channel attacks** (power, EM, timing).  
- Models:
  - **Bounded retrieval**
  - **Bounded leakage**
  - **Auxiliary input**
  - **Continuous leakage**
- Techniques:
  - Regular **key updates**.
  - **Leakage-tolerant IBE and ABE** schemes.
  - Continuous key-randomization for long-term resilience.

### **4.5 Privacy-Preserving Mechanisms**
- Protects **identities, attributes, and sensitive data**.
- Methods:
  - **Anonymous CP-ABE** (hidden policies).  
  - **Group-based ABE** for collaborative sharing.  
  - **Identity privacy in public auditing** using group keys and hash-based anonymity.  
  - **Chameleon hash** for hiding public keys.  
  - **Conditional privacy** in EHR systems (Zhang et al. 2019).

---

## 5. Open Research Issues

### **5.1 Privacy-Preserving Machine Learning in Cloud**
- Cloud-based ML must protect:
  - Data privacy during training.
  - Model confidentiality.
- Challenges:
  - Secure computation across untrusted cloud infrastructure.
  - Balancing privacy and efficiency.
- Future research:
  - Secure multi-party ML.
  - Homomorphic and federated learning integration.

### **5.2 Post-Quantum Encryption**
- Quantum computing threatens classical cryptography (RSA, ECC).  
- Promising post-quantum solutions:
  - **Hash-based cryptography**
  - **Lattice-based encryption (LWE, RLWE)**
  - **Code-based** and **multivariate polynomial** encryption.
- Needs:
  - Efficient key management.
  - Integration with cloud storage and blockchain.

---

## 6. Conclusions

The paper provides a **systematic classification of data security and privacy mechanisms** in cloud storage and emphasizes:
- **Encryption (IBE, ABE, HE, SE)** as the foundation for data protection.
- **Auditing and deletion verification** as essential for trust.
- **Leakage resilience and privacy preservation** for long-term cloud reliability.
- **Future research** should combine **AI, blockchain, post-quantum cryptography, and zero-trust architectures** to build a secure and intelligent cloud ecosystem.

---

### **Keywords**
Cloud storage • Data security • Encryption • Privacy • Access control • Cryptography • Homomorphic encryption • Attribute-based encryption • Leakage resilience • Post-quantum security
