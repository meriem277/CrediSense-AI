# CrediSense-AI Documentation

## Overview
CrediSense-AI is an advanced AI tool designed to assist users in managing their credit-related tasks. It provides features for tracking credit scores, analyzing credit reports, and offering personalized recommendations for credit improvement.

## Architecture
The architecture of CrediSense-AI is modular, consisting of several key components:
1. **Frontend:** Built using React for a dynamic user interface.
2. **Backend:** A RESTful API developed with Node.js and Express.
3. **Database:** Utilizes MongoDB for storing user data and credit information.

## Installation
To install CrediSense-AI, follow these steps:
1. Clone the repository:
   ```bash
   git clone https://github.com/meriem277/CrediSense-AI.git
   cd CrediSense-AI
   ```
2. Install dependencies:
   ```bash
   npm install
   ```

## Configuration
Before running the application, ensure to configure the environment variables in the `.env` file. Here’s an example:
```plaintext
DATABASE_URI=mongodb://localhost:27017/credinsense
API_KEY=your_api_key_here
```

## Usage
To start the application, run the following command:
```bash
npm start
```
Open your browser and navigate to `http://localhost:3000` to access the web application.

## API Documentation
The API provides several endpoints:
- **GET /api/credit-scores**: Retrieves the credit scores for the user.
- **POST /api/analyze-credit**: Analyzes a given credit report and provides feedback.
- **GET /api/recommendations**: Fetches personalized credit improvement recommendations.

## Testing
To run the tests, use the following command:
```bash
npm test
```

## Troubleshooting
If you encounter any issues:
- Ensure all environment variables are correctly set.
- Check the console for error messages.
- Review the documentation for known issues and solutions.