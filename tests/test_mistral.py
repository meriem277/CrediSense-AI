from transformers import AutoModelForCausalLM, AutoTokenizer, pipeline
import torch
import time

model_name = "mistralai/Mistral-7B-Instruct-v0.2"  # version Instruct, plus adaptée aux tests

# --- Chargement ---
tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForCausalLM.from_pretrained(
    model_name,
    torch_dtype=torch.float16,
    device_map="auto"   # ← décommenter obligatoire
)

# --- Test simple ---
prompts = [
    "What is the capital of France?",
    "Solve: 12 * 8 =",
    "Translate to French: Hello, how are you?"
]

pipe = pipeline("text-generation", model=model, tokenizer=tokenizer)

for prompt in prompts:
    start = time.time()
    result = pipe(prompt, max_new_tokens=100, do_sample=False)
    elapsed = time.time() - start
    
    print(f"Prompt : {prompt}")
    print(f"Réponse: {result[0]['generated_text']}")
    print(f"Temps  : {elapsed:.2f}s")
    print("-" * 50)