# API Test Case Generator
AI-powered API test case generator — Spring Boot backend + Ollama local LLM + HTML frontend.

## Prerequisites
- Java 17+
- Maven 3.8+
- [Ollama](https://ollama.com) installed and running

## 1. Setup Ollama

```bash
# Install Ollama (Windows: download from https://ollama.com)
# Then pull a model (pick one):
ollama pull llama3          # Recommended — best quality
ollama pull mistral         # Lighter, faster
ollama pull codellama       # Good for code generation

# Start Ollama server
ollama serve
```

## 2. Configure model (optional)

Edit `src/main/resources/application.properties`:
```properties
ollama.model=llama3          # Change to mistral, codellama, etc.
ollama.timeout-seconds=120   # Increase if your machine is slow
```

## 3. Run the application

```bash
mvn spring-boot:run
```

Then open: **http://localhost:8080**

## 4. Use the tool

1. Select input mode: **Endpoint**, **Swagger JSON**, or **Raw JSON**
2. Fill in the API details
3. Choose **coverage focus** and **number of test cases**
4. Click **Generate test cases**
5. Switch between **Gherkin** and **Java/RestAssured** tabs in the output panel

## Project Structure

```
src/
├── main/
│   ├── java/com/sdet/testgen/
│   │   ├── ApiTestGeneratorApplication.java   # Entry point
│   │   ├── config/AppConfig.java              # Bean config
│   │   ├── controller/TestGenController.java  # REST endpoints
│   │   ├── model/
│   │   │   ├── TestGenRequest.java            # Request DTO
│   │   │   └── TestGenResponse.java           # Response DTO
│   │   └── service/OllamaService.java         # Ollama LLM integration
│   └── resources/
│       ├── application.properties
│       └── static/index.html                  # Frontend UI
```

## API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET    | /api/health | Health check |
| POST   | /api/generate-tests | Generate test cases |

### Sample request body
```json
{
  "inputMode": "endpoint",
  "httpMethod": "POST",
  "endpointUrl": "/api/v1/users",
  "apiDescription": "Creates a new user. Requires name, email, role. Returns 201 with user object.",
  "coverageFocus": "all",
  "testCaseCount": 8
}
```

## Tips for better output
- Use **llama3** or **mistral** for best structured JSON output
- Add detailed descriptions including validations, auth, and error codes
- If the LLM returns a format error, try reducing `testCaseCount` to 5
- For Swagger input, paste the full `paths` section for best results
