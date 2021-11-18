package de.peterspace.nftdropper.cardano;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Validated
@Slf4j
@RequiredArgsConstructor
public class FileUtil {

	@Value("${working.dir.internal}")
	private String workingDirInternal;

	public String consumeFile(String filename) throws Exception {
		Path path = Paths.get(workingDirInternal, filename);
		String readString = Files.readString(path);
		Files.delete(path);
		return readString;
	}

	public boolean exists(String filename) throws Exception {
		return Files.isRegularFile(Paths.get(workingDirInternal, filename));
	}

	public String readFile(String filename) throws Exception {
		return Files.readString(Paths.get(workingDirInternal, filename));
	}

	public byte[] readFileBinary(String filename) throws Exception {
		return Files.readAllBytes(Paths.get(workingDirInternal, filename));
	}

	public void writeFile(String filename, String content) throws Exception {
		Files.writeString(Paths.get(workingDirInternal, filename), content);
	}

	public void writeFile(String filename, byte[] content) throws Exception {
		Files.write(Paths.get(workingDirInternal, filename), content);
	}

	public void removeFile(String filename) throws Exception {
		Files.delete(Paths.get(workingDirInternal, filename));
	}

}
