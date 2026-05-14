package parser;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class FileLoader {

    public void loadFile(String filePath) {

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {

            String line;

            while ((line = br.readLine()) != null) {

                // 1行表示
                System.out.println(line);

            }

        } catch (IOException e) {

            System.out.println("ファイル読み込みエラー");
            e.printStackTrace();

        }
    }
}