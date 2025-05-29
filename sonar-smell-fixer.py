#!/usr/bin/env python3
"""
SonarQube Code Smell Auto-Fixer
Automatiza la corrección de code smells usando LLM local
"""

import json
import subprocess
import requests
import os
import sys
from pathlib import Path
from typing import List, Dict, Any
import openai  # O la librería que uses para tu LLM local

class SonarSmellFixer:
    def __init__(self, sonar_url: str, sonar_token: str, project_key: str, llm_api_base: str = None):
        self.sonar_url = sonar_url
        self.sonar_token = sonar_token
        self.project_key = project_key
        self.llm_client = self._setup_llm_client(llm_api_base)

    def _setup_llm_client(self, api_base):
        """Configura el cliente LLM (Ollama, LocalAI, etc.)"""
        if api_base:
            openai.api_base = api_base
            openai.api_key = "not-needed-for-local"
        return openai

    def fetch_sonar_issues(self, severity: str = "MAJOR,MINOR,INFO") -> List[Dict[Any, Any]]:
        """Obtiene issues de SonarQube via API"""
        url = f"{self.sonar_url}/api/issues/search"
        params = {
            'componentKeys': self.project_key,
            'types': 'CODE_SMELL',
            'severities': severity,
            'ps': 500  # page size
        }

        headers = {'Authorization': f'Bearer {self.sonar_token}'}
        response = requests.get(url, params=params, headers=headers)

        if response.status_code != 200:
            raise Exception(f"Error fetching SonarQube issues: {response.status_code}")

        return response.json()['issues']

    def create_fix_branch(self):
        """Crea la rama fix/sonarqube-smells"""
        try:
            subprocess.run(['git', 'checkout', '-b', 'fix/sonarqube-smells'], check=True)
            print("✅ Rama fix/sonarqube-smells creada")
        except subprocess.CalledProcessError:
            print("⚠️  La rama ya existe, cambiando a ella...")
            subprocess.run(['git', 'checkout', 'fix/sonarqube-smells'], check=True)

    def generate_fix_prompt(self, issue: Dict[Any, Any]) -> str:
        """Genera el prompt para el LLM basado en el issue de SonarQube"""
        file_path = issue['component'].replace(f"{self.project_key}:", "")
        rule_key = issue['rule']
        message = issue['message']
        line = issue.get('line', 'N/A')

        # Leer el contenido del archivo
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                file_content = f.read()
        except FileNotFoundError:
            print(f"❌ Archivo no encontrado: {file_path}")
            return None

        prompt = f"""
Eres un experto desarrollador que debe corregir code smells detectados por SonarQube.

INFORMACIÓN DEL ISSUE:
- Archivo: {file_path}
- Regla: {rule_key}
- Mensaje: {message}
- Línea: {line}

CONTENIDO DEL ARCHIVO:
```
{file_content}
```

INSTRUCCIONES:
1. Identifica exactamente el problema descrito en el mensaje
2. Genera una solución que corrija el code smell
3. Mantén la funcionalidad original intacta
4. Responde SOLO con el código corregido completo
5. No incluyas explicaciones adicionales, solo el código

CÓDIGO CORREGIDO:
"""
        return prompt

    def apply_fix_with_llm(self, issue: Dict[Any, Any]) -> bool:
        """Aplica el fix usando LLM"""
        prompt = self.generate_fix_prompt(issue)
        if not prompt:
            return False

        try:
            # Llamada al LLM local (ajusta según tu setup)
            response = self.llm_client.Completion.create(
                model="codellama:7b",  # O tu modelo preferido
                prompt=prompt,
                max_tokens=4000,
                temperature=0.1
            )

            fixed_code = response.choices[0].text.strip()

            # Escribir el código corregido
            file_path = issue['component'].replace(f"{self.project_key}:", "")
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(fixed_code)

            print(f"✅ Fix aplicado a {file_path}")
            return True

        except Exception as e:
            print(f"❌ Error aplicando fix: {e}")
            return False

    def generate_commit_message(self, issue: Dict[Any, Any]) -> str:
        """Genera mensaje de commit descriptivo"""
        file_path = issue['component'].replace(f"{self.project_key}:", "")
        rule_key = issue['rule']
        message = issue['message']

        # Usar LLM para generar commit message molón
        prompt = f"""
Genera un mensaje de commit siguiendo conventional commits para este fix de SonarQube:

Archivo: {file_path}
Regla: {rule_key}
Issue: {message}

El mensaje debe:
- Empezar con "fix: "
- Ser descriptivo pero conciso
- Mencionar qué se eliminó/mejoró
- Incluir el beneficio (maintainability, compliance, etc.)

Ejemplo: "fix: eliminate hardcoded strings in UserService - centralize constants for better maintainability"

MENSAJE:
"""

        try:
            response = self.llm_client.Completion.create(
                model="codellama:7b",
                prompt=prompt,
                max_tokens=200,
                temperature=0.3
            )
            return response.choices[0].text.strip()
        except:
            # Fallback message
            return f"fix: resolve {rule_key} in {os.path.basename(file_path)}"

    def commit_fix(self, issue: Dict[Any, Any]):
        """Hace commit del fix"""
        try:
            subprocess.run(['git', 'add', '.'], check=True)

            commit_message = self.generate_commit_message(issue)
            subprocess.run(['git', 'commit', '-m', commit_message], check=True)

            print(f"✅ Commit realizado: {commit_message}")

        except subprocess.CalledProcessError as e:
            print(f"❌ Error en commit: {e}")

    def run_tests(self) -> bool:
        """Ejecuta tests para verificar que no se rompió nada"""
        print("🧪 Ejecutando tests...")
        try:
            # Ajusta según tu setup de tests
            result = subprocess.run(['npm', 'test'], capture_output=True, text=True)
            if result.returncode == 0:
                print("✅ Tests pasaron correctamente")
                return True
            else:
                print(f"❌ Tests fallaron: {result.stderr}")
                return False
        except FileNotFoundError:
            print("⚠️  No se encontró comando de tests, continuando...")
            return True

    def merge_to_main(self):
        """Hace merge a main si todo está OK"""
        try:
            subprocess.run(['git', 'checkout', 'main'], check=True)
            subprocess.run(['git', 'merge', 'fix/sonarqube-smells'], check=True)
            print("✅ Merge completado a main")
        except subprocess.CalledProcessError as e:
            print(f"❌ Error en merge: {e}")

    def process_all_smells(self):
        """Proceso principal"""
        print("🚀 Iniciando procesamiento de code smells...")

        # 1. Crear rama
        self.create_fix_branch()

        # 2. Obtener issues
        issues = self.fetch_sonar_issues()
        print(f"📋 Encontrados {len(issues)} code smells")

        # 3. Procesar uno a uno
        fixed_count = 0
        for i, issue in enumerate(issues, 1):
            print(f"\n🔧 Procesando {i}/{len(issues)}: {issue['message']}")

            if self.apply_fix_with_llm(issue):
                self.commit_fix(issue)
                fixed_count += 1
            else:
                print("⚠️  Saltando este issue...")

        print(f"\n✨ Proceso completado: {fixed_count}/{len(issues)} fixes aplicados")

        # 4. Ejecutar tests
        if self.run_tests():
            print("💚 ¿Hacer merge a main? (y/n)")
            if input().lower() == 'y':
                self.merge_to_main()
        else:
            print("❌ Tests fallaron, revisar manualmente antes del merge")

def main():
    # Configuración
    SONAR_URL = "http://localhost:9000"
    SONAR_TOKEN = "tu-token-aqui"  # Genéralo en SonarQube
    PROJECT_KEY = "tu-proyecto-key"
    LLM_API_BASE = "http://localhost:11434/v1"  # Para Ollama

    fixer = SonarSmellFixer(SONAR_URL, SONAR_TOKEN, PROJECT_KEY, LLM_API_BASE)
    fixer.process_all_smells()

if __name__ == "__main__":
    main()