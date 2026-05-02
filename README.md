Here is the cleanly formatted Markdown for your README.md file. You can copy the code block below and paste it directly into your document:

Markdown
## 🏃 Getting Started: Step-by-Step Guide

### 1. Obtain an OpenAI API Key
1. Go to the [OpenRouter.ai](https://openrouter.ai/).
2. Navigate to Keys in the dashboard. 
3. Click Create Key, give it a name, and copy the string (it should start with sk-or-v1-).

### 2. Configure Environment Variables
The application reads the key from your system environment for security.

- **macOS / Linux:**
  ```bash
    export OPENROUTER_API_KEY='your_actual_key_here'  
  ```
- **Windows (PowerShell):**
    ```bash
    $env:OPENROUTER_API_KEY="your_actual_key_here"
    ```
### Prerequisites
   Java JDK 17+: Verify with java -version. 
   Gradle: The project includes the Gradle wrapper.

### 4. Build and Run
   Navigate to the project root:
```bash
 cd ScienceAssistant
```

Build the project:
```bash
./gradlew build
```
Start the server:
```bash
./gradlew run
```
The server is active when you see: Responding at http://0.0.0.0:8080

### 5. Access the GUI
   Open your browser and go to: http://localhost:8080
    Enter a query (e.g., "Find the dosage of Rapamycin used in human fibroblasts for autophagy induction").
    Click Analyze and watch the Agent's reasoning process.